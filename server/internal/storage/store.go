// Package storage owns a private, locked archive. One fsync/rename state publication
// acknowledges metadata and manifests; blobs are immutable and durable before references.
package storage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"timelapse/internal/merge"
)

const ChunkSize = 8 << 20

var ErrConflict = errors.New("identity, revision or offset conflict")
var ErrRecovery = errors.New("archive recovery required")

type Source struct {
	CreatedAt      int64           `json:"createdAt"`
	PlannedMinutes int             `json:"plannedMinutes"`
	State          string          `json:"state"`
	Archived       bool            `json:"archived"`
	Deleted        bool            `json:"deleted"`
	Timing         json.RawMessage `json:"timing"`
	Links          json.RawMessage `json:"links"`
}

func (s Source) Valid() bool {
	return s.CreatedAt >= 0 && s.PlannedMinutes >= 1 && s.PlannedMinutes <= 1440 &&
		(s.State == "PLANNED" || s.State == "RUNNING" || s.State == "ENDED") && !(s.Archived && s.State == "RUNNING") &&
		s.validTimingAndLinks()
}

type Record struct {
	Device   string                 `json:"device"`
	ID       string                 `json:"id"`
	Sequence uint64                 `json:"sequence"`
	Source   Source                 `json:"source"`
	Fields   map[string]merge.Field `json:"fields"`
	SyncedAt int64                  `json:"syncedAt"`
}
type SourceUpdate struct {
	ID       string            `json:"id"`
	Sequence uint64            `json:"sequence"`
	Source   Source            `json:"source"`
	Initial  map[string]string `json:"initial"`
}
type Patch struct {
	Device string                `json:"device"`
	ID     string                `json:"id"`
	Fields map[string]merge.Edit `json:"fields"`
}
type Chunk struct {
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}
type Video struct {
	Device    string  `json:"device"`
	ID        string  `json:"id"`
	Recording string  `json:"recording"`
	Size      int64   `json:"size"`
	SHA256    string  `json:"sha256"`
	Offset    int64   `json:"offset"`
	Chunks    []Chunk `json:"chunks"`
	Complete  bool    `json:"complete"`
	Missing   bool    `json:"missing"`
	SyncedAt  int64   `json:"syncedAt"`
}
type Receipt struct {
	Digest   string          `json:"digest"`
	Response json.RawMessage `json:"response"`
}
type State struct {
	Version  int                `json:"version"`
	Records  map[string]Record  `json:"records"`
	Videos   map[string]Video   `json:"videos"`
	Receipts map[string]Receipt `json:"receipts"`
}
type Store struct {
	mu         sync.Mutex
	root       *os.Root
	lock       *os.File
	state      State
	blocked    error
	stateBytes []byte
	path       string
	// Fault is fixture-only; production leaves it nil. Boundaries cover publication uncertainty.
	Fault func(string) error
}

func privateTree(path string) error {
	return filepath.Walk(path, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 || (!info.IsDir() && !info.Mode().IsRegular()) {
			return errors.New("aliased or nonregular archive retained")
		}
		mode := os.FileMode(0600)
		if info.IsDir() {
			mode = 0700
		}
		if info.Mode().Perm() != mode {
			return errors.New("archive permissions must be 0700/0600")
		}
		if st, ok := info.Sys().(*syscall.Stat_t); ok && !info.IsDir() && st.Nlink != 1 {
			return errors.New("hardlinked archive retained")
		}
		return nil
	})
}
func Open(path string) (*Store, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return nil, errors.New("absolute canonical archive path required")
	}
	real, err := filepath.EvalSymlinks(filepath.Dir(path))
	if err != nil || real != filepath.Dir(path) {
		return nil, errors.New("aliased archive parent")
	}
	if err = os.MkdirAll(path, 0700); err != nil {
		return nil, err
	}
	if err = privateTree(path); err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(path)
	if err != nil {
		return nil, err
	}
	fail := func(e error) (*Store, error) { root.Close(); return nil, e }
	_, lockError := root.Lstat("lock")
	if lockError != nil && !os.IsNotExist(lockError) {
		return fail(lockError)
	}
	hadLock := lockError == nil
	newArchive := false
	lock, err := root.OpenFile("lock", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return fail(err)
	}
	if err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		lock.Close()
		return fail(errors.New("archive already owned"))
	}
	s := &Store{root: root, lock: lock, state: State{1, map[string]Record{}, map[string]Video{}, map[string]Receipt{}}, path: path}
	raw, err := root.ReadFile("state.json")
	if err == nil {
		s.stateBytes = append([]byte{}, raw...)
		err = Strict(raw, &s.state)
		if err == nil && (s.state.Version != 1 || s.state.Records == nil || s.state.Videos == nil || s.state.Receipts == nil) {
			err = errors.New("unsupported archive schema")
		}
	} else if os.IsNotExist(err) {
		if hadLock {
			err = errors.New("initialized archive lost state.json; recovery required")
		} else {
			err = nil
			newArchive = true
		}
	}
	if err != nil {
		s.Close()
		return nil, err
	}
	for key, v := range s.state.Videos {
		if key != Key(v.Device, v.ID) || !merge.ID(v.Device) || !merge.ID(v.ID) || !hashOK(v.SHA256) || v.Size < 1 || v.Offset < 0 || v.Offset > v.Size {
			s.Close()
			return nil, errors.New("invalid video manifest retained")
		}
		var offset int64
		for _, chunk := range v.Chunks {
			if chunk.Size < 1 || chunk.Size > ChunkSize || chunk.Size > v.Size-offset || !hashOK(chunk.SHA256) {
				s.Close()
				return nil, errors.New("invalid chunk manifest")
			}
			if err = s.verifyFile(chunkName(key, offset), chunk.Size, chunk.SHA256); err != nil {
				s.Close()
				return nil, err
			}
			offset += chunk.Size
		}
		if offset != v.Offset || (v.Complete && v.Offset != v.Size) {
			s.Close()
			return nil, errors.New("invalid committed offset")
		}
		if v.Complete {
			if err = s.verifyFile(blobName(key), v.Size, v.SHA256); err != nil {
				s.Close()
				return nil, err
			}
		}
	}
	for key, r := range s.state.Records {
		if key != Key(r.Device, r.ID) || !merge.ID(r.Device) || !merge.ID(r.ID) || r.Sequence < 1 || r.SyncedAt < 0 || !r.Source.Valid() || !r.Source.linksBelongTo(r.ID) || len(r.Fields) != 4 {
			s.Close()
			return nil, errors.New("invalid record retained")
		}
		for n, f := range r.Fields {
			if !merge.ValidField(n, f) {
				s.Close()
				return nil, errors.New("invalid field retained")
			}
		}
	}
	for key, r := range s.state.Receipts {
		parts := strings.Split(key, "_")
		if len(parts) != 2 || !merge.ID(parts[0]) || !merge.ID(parts[1]) || !hashOK(r.Digest) || !json.Valid(r.Response) {
			s.Close()
			return nil, errors.New("invalid receipt retained")
		}
	}
	if newArchive {
		if err = s.commit(s.state); err != nil {
			s.Close()
			return nil, err
		}
	}
	return s, nil
}
func (s *Store) Close() error { s.lock.Close(); return s.root.Close() }
func Strict(raw []byte, v any) error {
	if err := uniqueJSON(raw); err != nil {
		return err
	}
	if err := requiredFields(raw, v); err != nil {
		return err
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return err
	}
	if d.Decode(new(any)) != io.EOF {
		return errors.New("trailing JSON")
	}
	return nil
}
func Key(device, id string) string { return device + "_" + id }
func hashOK(h string) bool {
	b, e := hex.DecodeString(h)
	return e == nil && len(b) == 32 && strings.ToLower(h) == h
}
func Digest(raw []byte) string { h := sha256.Sum256(raw); return hex.EncodeToString(h[:]) }
func (s *Store) fault(name string) error {
	if s.Fault != nil {
		return s.Fault(name)
	}
	return nil
}
func (s *Store) atomic(name string, write func(*os.File) error) error {
	// Random retained staging files are never trusted as committed state on restart.
	token := fmt.Sprintf("stage-%d-%d", time.Now().UnixNano(), os.Getpid())
	f, err := s.root.OpenFile(token, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer f.Close()
	if err = s.fault("before-write"); err != nil {
		return err
	}
	if err = write(f); err != nil {
		return err
	}
	if err = s.fault("before-sync"); err != nil {
		return err
	}
	if err = f.Sync(); err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	if err = s.fault("before-rename"); err != nil {
		return err
	}
	if name != "state.json" {
		if _, e := s.root.Lstat(name); e == nil {
			// A crash may have left an unreferenced immutable object. Reuse only
			// byte-identical content; never overwrite an unknown retained object.
			staged, e := s.root.Open(token)
			if e != nil {
				return e
			}
			h := sha256.New()
			size, e := io.Copy(h, staged)
			staged.Close()
			if e != nil {
				return e
			}
			if e = s.verifyFile(name, size, hex.EncodeToString(h.Sum(nil))); e != nil {
				return e
			}
			return s.syncDirectory()
		} else if !os.IsNotExist(e) {
			return e
		}
	}
	if err = s.root.Rename(token, name); err != nil {
		return err
	}
	if err = s.fault("after-rename"); err != nil {
		return err
	}
	return s.syncDirectory()
}
func (s *Store) syncDirectory() error {
	dir, err := s.root.Open(".")
	if err != nil {
		return err
	}
	defer dir.Close()
	if err = dir.Sync(); err != nil {
		return err
	}
	return s.fault("after-directory-sync")
}
func clone(st State) State {
	raw, _ := json.Marshal(st)
	var next State
	_ = json.Unmarshal(raw, &next)
	return next
}
func (s *Store) commit(next State) error {
	raw, err := json.Marshal(next)
	if err != nil {
		return err
	}
	if err = s.atomic("state.json", func(f *os.File) error { _, e := f.Write(raw); return e }); err != nil {
		s.blocked = err
		return errors.Join(ErrRecovery, err)
	}
	s.state = next
	s.stateBytes = raw
	return nil
}

// Check rejects out-of-owner changes rather than overwriting an archive from stale memory.
func (s *Store) Check() error { s.mu.Lock(); defer s.mu.Unlock(); return s.check() }
func (s *Store) check() error {
	if s.blocked != nil {
		return ErrRecovery
	}
	if err := privateTree(s.path); err != nil {
		s.blocked = err
		return err
	}
	raw, err := s.root.ReadFile("state.json")
	if s.stateBytes == nil && os.IsNotExist(err) {
		return nil
	}
	if err != nil || !bytes.Equal(raw, s.stateBytes) {
		s.blocked = errors.New("archive bytes changed outside owner; retained")
		return s.blocked
	}
	return nil
}

// Mutate stores the exact response with the request, so lost replies never replay edits.
func (s *Store) Mutate(actor, mutation string, request any, apply func(*State) (any, error)) (json.RawMessage, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.check(); err != nil {
		return nil, err
	}
	if !merge.ID(actor) || !merge.ID(mutation) {
		return nil, errors.New("invalid identity")
	}
	raw, err := json.Marshal(request)
	if err != nil {
		return nil, err
	}
	digest := Digest(raw)
	key := Key(actor, mutation)
	if prior, ok := s.state.Receipts[key]; ok {
		if prior.Digest != digest {
			return nil, ErrConflict
		}
		return prior.Response, nil
	}
	next := clone(s.state)
	reply, err := apply(&next)
	if err != nil {
		return nil, err
	}
	response, err := json.Marshal(reply)
	if err != nil {
		return nil, err
	}
	next.Receipts[key] = Receipt{digest, response}
	if err = s.commit(next); err != nil {
		return nil, err
	}
	return response, nil
}
func (s *Store) Source(actor, mutation string, updates []SourceUpdate) (json.RawMessage, error) {
	return s.Mutate(actor, mutation, struct {
		Kind    string
		Updates []SourceUpdate
	}{"source", updates}, func(st *State) (any, error) {
		if len(updates) < 1 || len(updates) > 100 {
			return nil, errors.New("batch limit")
		}
		result := []Record{}
		for _, u := range updates {
			if !merge.ID(u.ID) || u.Sequence < 1 || !u.Source.Valid() || !u.Source.linksBelongTo(u.ID) {
				return nil, errors.New("invalid source record")
			}
			key := Key(actor, u.ID)
			r, exists := st.Records[key]
			if exists {
				if u.Sequence <= r.Sequence {
					return nil, ErrConflict
				}
				if r.Source.Deleted && !u.Source.Deleted {
					return nil, errors.New("deleted source identity cannot resurrect")
				}
			} else {
				if len(u.Initial) != 4 {
					return nil, errors.New("four initial fields required")
				}
				r = Record{Device: actor, ID: u.ID, Fields: map[string]merge.Field{}}
				for n, v := range u.Initial {
					if !merge.Valid(n, v) {
						return nil, errors.New("invalid initial metadata")
					}
					field := merge.New(v)
					field.Changes = []merge.Variant{{Value: v, Device: actor, Mutation: mutation}}
					r.Fields[n] = field
				}
			}
			r.Source = u.Source
			r.Sequence = u.Sequence
			r.SyncedAt = time.Now().UnixMilli()
			st.Records[key] = r
			result = append(result, r)
		}
		return result, nil
	})
}
func (s *Store) Patch(actor, mutation string, p Patch) (json.RawMessage, error) {
	return s.Mutate(actor, mutation, struct {
		Kind  string
		Patch Patch
	}{"patch", p}, func(st *State) (any, error) {
		if !merge.ID(p.Device) || !merge.ID(p.ID) || len(p.Fields) < 1 || len(p.Fields) > 4 {
			return nil, errors.New("invalid patch")
		}
		key := Key(p.Device, p.ID)
		r, ok := st.Records[key]
		if !ok {
			return nil, os.ErrNotExist
		}
		for n, e := range p.Fields {
			f, exists := r.Fields[n]
			if !exists {
				return nil, errors.New("protected or unknown field")
			}
			next, err := merge.Apply(n, f, e, actor, mutation)
			if err != nil {
				return nil, err
			}
			r.Fields[n] = next
		}
		r.SyncedAt = time.Now().UnixMilli()
		st.Records[key] = r
		return r, nil
	})
}
func (s *Store) Snapshot() State                { s.mu.Lock(); defer s.mu.Unlock(); return clone(s.state) }
func chunkName(key string, offset int64) string { return fmt.Sprintf("chunk-%s-%d", key, offset) }
func blobName(key string) string                { return "video-" + key + ".mp4" }
func (s *Store) verifyFile(name string, size int64, digest string) error {
	info, e := s.root.Lstat(name)
	if e != nil {
		return e
	}
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || info.Sys().(*syscall.Stat_t).Nlink != 1 {
		return errors.New("aliased media retained")
	}
	f, e := s.root.Open(name)
	if e != nil {
		return e
	}
	defer f.Close()
	h := sha256.New()
	n, e := io.Copy(h, f)
	if e != nil {
		return e
	}
	if n != size || hex.EncodeToString(h.Sum(nil)) != digest {
		return errors.New("committed media mismatch; retained")
	}
	return nil
}
func (s *Store) Begin(actor, mutation string, v Video) (json.RawMessage, error) {
	return s.Mutate(actor, mutation, struct {
		Kind                  string
		ID, Recording, SHA256 string
		Size                  int64
	}{"begin", v.ID, v.Recording, v.SHA256, v.Size}, func(st *State) (any, error) {
		if !merge.ID(v.ID) || v.Size < 1 || !hashOK(v.SHA256) || (v.Recording != "" && !recordingPattern.MatchString(v.Recording)) {
			return nil, errors.New("invalid MP4 manifest")
		}
		key := Key(actor, v.ID)
		if old, ok := st.Videos[key]; ok {
			if old.Size != v.Size || old.SHA256 != v.SHA256 || old.Recording != v.Recording {
				return nil, ErrConflict
			}
			return old, nil
		}
		v = Video{Device: actor, ID: v.ID, Recording: v.Recording, Size: v.Size, SHA256: v.SHA256, Chunks: []Chunk{}}
		st.Videos[key] = v
		return v, nil
	})
}
func (s *Store) Chunk(actor, id string, offset int64, digest string, body io.Reader) (Video, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.check(); err != nil {
		return Video{}, err
	}
	if !merge.ID(actor) || !merge.ID(id) || offset < 0 || !hashOK(digest) {
		return Video{}, errors.New("invalid chunk")
	}
	key := Key(actor, id)
	v, ok := s.state.Videos[key]
	if !ok {
		return v, os.ErrNotExist
	}
	// Reconcile the offset via GET before retry; do not accept changed overlapping bytes.
	if v.Complete || offset != v.Offset {
		return v, ErrConflict
	}
	var size int64
	err := s.atomic(chunkName(key, offset), func(f *os.File) error {
		h := sha256.New()
		n, e := io.Copy(io.MultiWriter(f, h), io.LimitReader(body, ChunkSize+1))
		size = n
		if e != nil {
			return e
		}
		if n < 1 || n > ChunkSize || n > v.Size-offset || hex.EncodeToString(h.Sum(nil)) != digest {
			return errors.New("chunk size/hash mismatch")
		}
		return nil
	})
	if err != nil {
		s.blocked = err
		return v, errors.Join(ErrRecovery, err)
	}
	v.Chunks = append(v.Chunks, Chunk{size, digest})
	v.Offset += size
	next := clone(s.state)
	next.Videos[key] = v
	if err = s.commit(next); err != nil {
		return Video{}, err
	}
	return v, nil
}
func (s *Store) Complete(actor, mutation, id string) (json.RawMessage, error) {
	return s.Mutate(actor, mutation, struct{ Kind, ID string }{"complete", id}, func(st *State) (any, error) {
		if !merge.ID(id) {
			return nil, errors.New("invalid identity")
		}
		key := Key(actor, id)
		v, ok := st.Videos[key]
		if !ok {
			return nil, os.ErrNotExist
		}
		if v.Complete {
			return v, nil
		}
		if v.Offset != v.Size {
			return nil, ErrConflict
		}
		err := s.atomic(blobName(key), func(f *os.File) error {
			h := sha256.New()
			var offset int64
			for _, c := range v.Chunks {
				in, e := s.root.Open(chunkName(key, offset))
				if e != nil {
					return e
				}
				ch := sha256.New()
				n, e := io.Copy(io.MultiWriter(f, h, ch), in)
				in.Close()
				if e != nil {
					return e
				}
				if n != c.Size || hex.EncodeToString(ch.Sum(nil)) != c.SHA256 {
					return errors.New("chunk changed")
				}
				offset += n
			}
			if offset != v.Size || hex.EncodeToString(h.Sum(nil)) != v.SHA256 {
				return errors.New("final digest mismatch")
			}
			return nil
		})
		if err != nil {
			s.blocked = err
			return nil, errors.Join(ErrRecovery, err)
		}
		v.Complete = true
		v.SyncedAt = time.Now().UnixMilli()
		st.Videos[key] = v
		return v, nil
	})
}
func (s *Store) Media(device, id string) (*os.File, Video, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !merge.ID(device) || !merge.ID(id) {
		return nil, Video{}, os.ErrNotExist
	}
	v, ok := s.state.Videos[Key(device, id)]
	if !ok || !v.Complete {
		return nil, v, os.ErrNotExist
	}
	if err := s.check(); err != nil {
		return nil, v, err
	}
	f, err := s.root.Open(blobName(Key(device, id)))
	return f, v, err
}
func (s *Store) Space() (bool, error) {
	var stat syscall.Statfs_t
	if err := syscall.Fstatfs(int(s.lock.Fd()), &stat); err != nil {
		return false, err
	}
	return LowSpace(stat.Bavail, stat.Blocks), nil
}
func LowSpace(free, total uint64) bool { return total > 0 && float64(free)/float64(total) < 0.1 }
func (s *Store) MarkMissing(actor, mutation, id string) (json.RawMessage, error) {
	return s.Mutate(actor, mutation, struct{ Kind, ID string }{"missing", id}, func(st *State) (any, error) {
		if !merge.ID(id) {
			return nil, errors.New("invalid ID")
		}
		key := Key(actor, id)
		v, ok := st.Videos[key]
		if !ok {
			return nil, os.ErrNotExist
		}
		v.Missing = true
		st.Videos[key] = v
		return v, nil
	})
}
