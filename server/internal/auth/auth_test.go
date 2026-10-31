package auth

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const deviceID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

func privateParent(t *testing.T) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "private")
	if e := os.Mkdir(p, 0700); e != nil {
		t.Fatal(e)
	}
	return p
}
func TestPBKDFKnownVectorAndRandomSalt(t *testing.T) {
	got, e := Derive("password", "73616c74000000000000000000000000")
	if e != nil || got != "c074471c70b720940e334ed47542ec08b115e2970c6befd22426a3cb3163c0a5" {
		t.Fatal(e)
	}
	a, e := Init("owner", "a long private password")
	if e != nil {
		t.Fatal(e)
	}
	b, _ := Init("owner", "a long private password")
	if a.Salt == b.Salt || a.Password == b.Password || !a.Browser("owner", "a long private password") || a.Browser("other", "a long private password") || a.Browser("owner", "wrong") {
		t.Fatal("password derivation/authentication")
	}
	if strings.Contains(a.Password, "private") {
		t.Fatal("plaintext password")
	}
}
func TestDeviceTokensAreHashOnlyAndRevocable(t *testing.T) {
	c, _ := Init("owner", "a long private password")
	token := Random()
	hash := sha256.Sum256([]byte(token))
	c.Devices[deviceID] = Device{"Pixel", hex.EncodeToString(hash[:]), false}
	if id, ok := c.Device(token); !ok || id != deviceID {
		t.Fatal("device denied")
	}
	if _, ok := c.Device(Random()); ok {
		t.Fatal("unknown device authorized")
	}
	if _, ok := c.Device("invalid"); ok {
		t.Fatal("invalid token authorized")
	}
	d := c.Devices[deviceID]
	d.Revoked = true
	c.Devices[deviceID] = d
	if _, ok := c.Device(token); ok {
		t.Fatal("revoked token authorized")
	}
}
func TestPrivateAtomicCredentialsAndNoImplicitOverwrite(t *testing.T) {
	parent := privateParent(t)
	path := filepath.Join(parent, "credentials.json")
	c, _ := Init("owner", "a long private password")
	if e := Save(path, func(target *Credentials) error { *target = c; return nil }, true); e != nil {
		t.Fatal(e)
	}
	if e := Save(path, func(*Credentials) error { return nil }, true); e == nil {
		t.Fatal("existing credentials replaced")
	}
	loaded, e := Load(path)
	if e != nil || loaded.Password != c.Password {
		t.Fatal(e)
	}
	if e = Save(path, func(target *Credentials) error { target.Username = "new-owner"; return nil }, false); e != nil {
		t.Fatal(e)
	}
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0600 {
		t.Fatal("public credentials")
	}
	raw, _ := os.ReadFile(path)
	if strings.Contains(string(raw), "a long private password") {
		t.Fatal("secret persisted")
	}
}
func TestCredentialAliasesPermissionsAndUnsupportedVersionRejected(t *testing.T) {
	for _, kind := range []string{"symlink", "hardlink", "public", "version"} {
		t.Run(kind, func(t *testing.T) {
			path := filepath.Join(privateParent(t), "credentials.json")
			c, _ := Init("owner", "a long private password")
			if e := Save(path, func(target *Credentials) error { *target = c; return nil }, true); e != nil {
				t.Fatal(e)
			}
			switch kind {
			case "symlink":
				alias := path + "-alias"
				os.Symlink(path, alias)
				path = alias
			case "hardlink":
				os.Link(path, path+"-alias")
			case "public":
				os.Chmod(path, 0644)
			case "version":
				raw, _ := os.ReadFile(path)
				os.WriteFile(path, []byte(strings.Replace(string(raw), `"version":1`, `"version":2`, 1)), 0600)
			}
			if _, e := Load(path); e == nil {
				t.Fatal("unsafe credentials accepted")
			}
		})
	}
}
func TestSecretFileOnlyAndPasswordBounds(t *testing.T) {
	path := filepath.Join(t.TempDir(), "password")
	os.WriteFile(path, []byte("secret\n"), 0600)
	got, e := ReadSecret(path)
	if e != nil || got != "secret" {
		t.Fatal(e)
	}
	os.Chmod(path, 0644)
	if _, e = ReadSecret(path); e == nil {
		t.Fatal("public input read")
	}
	if _, e = Init("owner", "short"); e == nil {
		t.Fatal("weak password accepted")
	}
}
