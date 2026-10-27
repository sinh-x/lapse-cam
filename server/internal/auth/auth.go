// Package auth stores only device-token hashes and a salted browser password verifier.
package auth

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"timelapse/internal/merge"
	"timelapse/internal/storage"
)

const Iterations = 600000

type Device struct {
	Name    string `json:"name"`
	Hash    string `json:"hash"`
	Revoked bool   `json:"revoked"`
}
type Credentials struct {
	Version  int               `json:"version"`
	Username string            `json:"username"`
	Salt     string            `json:"salt"`
	Password string            `json:"password"`
	Devices  map[string]Device `json:"devices"`
}

func Random() string {
	var b [32]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b[:])
}
func Derive(password, salt string) (string, error) {
	b, e := hex.DecodeString(salt)
	if e != nil || len(b) != 16 {
		return "", errors.New("invalid salt")
	}
	key, e := pbkdf2.Key(sha256.New, password, b, Iterations, 32)
	return hex.EncodeToString(key), e
}
func validHash(s string) bool {
	b, e := hex.DecodeString(s)
	return e == nil && len(b) == 32 && hex.EncodeToString(b) == s
}
func (c Credentials) Validate() error {
	if c.Version != 1 || c.Username == "" || len(c.Username) > 120 || !validHash(c.Password) || c.Devices == nil {
		return errors.New("invalid credentials")
	}
	b, e := hex.DecodeString(c.Salt)
	if e != nil || len(b) != 16 {
		return errors.New("invalid salt")
	}
	seen := map[string]bool{}
	for id, d := range c.Devices {
		if !merge.ID(id) || !validHash(d.Hash) || strings.TrimSpace(d.Name) == "" || len(d.Name) > 120 || seen[d.Hash] {
			return errors.New("invalid or duplicate device credential")
		}
		seen[d.Hash] = true
	}
	return nil
}
func Init(user, password string) (Credentials, error) {
	if len(password) < 12 || len(password) > 1024 {
		return Credentials{}, errors.New("password must contain 12–1024 bytes")
	}
	salt := Random()[:32]
	hash, e := Derive(password, salt)
	c := Credentials{1, user, salt, hash, map[string]Device{}}
	if e != nil {
		return c, e
	}
	return c, c.Validate()
}
func CheckPrivateFile(path string) error { return privateFile(path) }
func privateFile(path string) error {
	if !filepath.IsAbs(path) {
		return errors.New("absolute private file path required")
	}
	real, e := filepath.EvalSymlinks(path)
	if e != nil || real != path {
		return errors.New("aliased credential file")
	}
	info, e := os.Lstat(path)
	if e != nil {
		return e
	}
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || info.Sys().(*syscall.Stat_t).Nlink != 1 {
		return errors.New("private file must be regular, unaliased, mode0600")
	}
	return nil
}
func ReadSecret(path string) (string, error) {
	if e := privateFile(path); e != nil {
		return "", e
	}
	b, e := os.ReadFile(path)
	if e != nil {
		return "", e
	}
	if len(b) > 4096 {
		return "", errors.New("secret file too large")
	}
	return strings.TrimSuffix(string(b), "\n"), nil
}
func Load(path string) (Credentials, error) {
	var c Credentials
	if e := privateFile(path); e != nil {
		return c, e
	}
	b, e := os.ReadFile(path)
	if e != nil {
		return c, e
	}
	if len(b) > 1<<20 {
		return c, errors.New("credentials too large")
	}
	if e = storage.Strict(b, &c); e != nil {
		return c, e
	}
	return c, c.Validate()
}

// Save requires a private parent and locks a separate stable inode across replacements.
func Save(path string, change func(*Credentials) error, create bool) error {
	parent := filepath.Dir(path)
	real, e := filepath.EvalSymlinks(parent)
	if e != nil || real != parent || !filepath.IsAbs(path) {
		return errors.New("canonical credential parent required")
	}
	info, e := os.Stat(parent)
	if e != nil || info.Mode().Perm() != 0700 {
		return errors.New("credential parent must be0700")
	}
	lock, e := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if e != nil {
		return e
	}
	defer lock.Close()
	lockInfo, e := lock.Stat()
	if e != nil {
		return e
	}
	if !lockInfo.Mode().IsRegular() || lockInfo.Mode().Perm() != 0600 || lockInfo.Sys().(*syscall.Stat_t).Nlink != 1 {
		return errors.New("aliased or insecure credential lock")
	}
	if e = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		return e
	}
	var c Credentials
	if create {
		if _, e = os.Lstat(path); !os.IsNotExist(e) {
			return errors.New("credentials already exist or cannot be checked")
		}
	} else {
		c, e = Load(path)
		if e != nil {
			return e
		}
	}
	if e = change(&c); e != nil {
		return e
	}
	if e = c.Validate(); e != nil {
		return e
	}
	b, e := json.Marshal(c)
	if e != nil {
		return e
	}
	tmp, e := os.CreateTemp(parent, "credentials-stage-")
	if e != nil {
		return e
	}
	defer tmp.Close()
	if _, e = tmp.Write(b); e != nil {
		return e
	}
	if e = tmp.Sync(); e != nil {
		return e
	}
	if e = tmp.Close(); e != nil {
		return e
	}
	if e = os.Rename(tmp.Name(), path); e != nil {
		return e
	}
	dir, e := os.Open(parent)
	if e != nil {
		return e
	}
	defer dir.Close()
	return dir.Sync()
}
func (c Credentials) Device(token string) (string, bool) {
	if !validHash(token) {
		return "", false
	}
	digest := sha256.Sum256([]byte(token))
	match := ""
	for id, d := range c.Devices {
		hash, _ := hex.DecodeString(d.Hash)
		if subtle.ConstantTimeCompare(digest[:], hash) == 1 && !d.Revoked {
			match = id
		}
	}
	return match, match != ""
}
func (c Credentials) Browser(user, password string) bool {
	hash, e := Derive(password, c.Salt)
	if e != nil {
		return false
	}
	u := sha256.Sum256([]byte(user))
	expected := sha256.Sum256([]byte(c.Username))
	return subtle.ConstantTimeCompare(u[:], expected[:]) == 1 && subtle.ConstantTimeCompare([]byte(hash), []byte(c.Password)) == 1
}
