package storage

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"timelapse/internal/merge"
)

const phoneA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const phoneB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const workID = "11111111-1111-4111-8111-111111111111"
const videoID = "22222222-2222-4222-8222-222222222222"

func mutation(n int) string { return fmt.Sprintf("00000000-0000-4000-8000-%012x", n) }
func fixture(t *testing.T) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "archive")
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return s, path
}
func update() SourceUpdate {
	return SourceUpdate{workID, 1, Source{CreatedAt: 100, PlannedMinutes: 25, State: "PLANNED", Timing: json.RawMessage(`{"start":null,"deadline":null,"endElapsed":null,"reason":null,"confirmedElapsed":0}`), Links: json.RawMessage(`[]`)}, map[string]string{"title": "original", "type": "work", "description": "", "expectedOutcome": ""}}
}
func addWork(t *testing.T, s *Store) {
	t.Helper()
	if _, e := s.Source(phoneA, mutation(1), []SourceUpdate{update()}); e != nil {
		t.Fatal(e)
	}
}
func begin(t *testing.T, s *Store, data []byte) {
	t.Helper()
	if _, e := s.Begin(phoneA, mutation(2), Video{ID: videoID, Size: int64(len(data)), SHA256: Digest(data), Recording: "100-" + workID}); e != nil {
		t.Fatal(e)
	}
}
func putChunks(t *testing.T, s *Store, data []byte) {
	t.Helper()
	for offset := 0; offset < len(data); {
		end := min(len(data), offset+ChunkSize)
		chunk := data[offset:end]
		if _, e := s.Chunk(phoneA, videoID, int64(offset), Digest(chunk), bytes.NewReader(chunk)); e != nil {
			t.Fatal(e)
		}
		offset = end
	}
}
func TestDurableSourceAndIdempotentLostReply(t *testing.T) {
	s, path := fixture(t)
	u := []SourceUpdate{update()}
	first, e := s.Source(phoneA, mutation(1), u)
	if e != nil {
		t.Fatal(e)
	}
	if e = s.Close(); e != nil {
		t.Fatal(e)
	}
	reopened, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	second, e := reopened.Source(phoneA, mutation(1), u)
	if e != nil || !bytes.Equal(first, second) {
		t.Fatal("lost response did not reconcile", e)
	}
	u[0].Initial["title"] = "changed"
	if _, e = reopened.Source(phoneA, mutation(1), u); !errors.Is(e, ErrConflict) {
		t.Fatal("mutation ID was reused")
	}
	if len(reopened.Snapshot().Records) != 1 {
		t.Fatal("duplicate record")
	}
}
func TestForeignEditsCannotChangeSourceOrOverwriteMergedFields(t *testing.T) {
	s, _ := fixture(t)
	addWork(t, s)
	patch := Patch{phoneA, workID, map[string]merge.Edit{"title": {Value: "remote", Base: 1}}}
	if _, e := s.Patch(phoneB, mutation(1), patch); e != nil {
		t.Fatal(e)
	}
	u := update()
	u.Sequence = 2
	u.Source.Deleted = true
	if _, e := s.Source(phoneA, mutation(3), []SourceUpdate{u}); e != nil {
		t.Fatal(e)
	}
	r := s.Snapshot().Records[Key(phoneA, workID)]
	if r.Fields["title"].Value != "remote" || !r.Source.Deleted {
		t.Fatal("stale source overwrote shared metadata")
	}
	u.Sequence = 3
	u.Source.Deleted = false
	if _, e := s.Source(phoneA, mutation(4), []SourceUpdate{u}); e == nil {
		t.Fatal("deleted identity resurrected")
	}
	patch.Fields = map[string]merge.Edit{"state": {Value: "RUNNING", Base: 1}}
	if _, e := s.Patch(phoneB, mutation(2), patch); e == nil {
		t.Fatal("foreign timer write")
	}
	if _, e := s.Source(phoneB, mutation(3), []SourceUpdate{update()}); e != nil {
		t.Fatal(e)
	}
	if len(s.Snapshot().Records) != 2 {
		t.Fatal("same local UUID collided across devices")
	}
}
func TestBatchRollbackAndLimits(t *testing.T) {
	s, _ := fixture(t)
	u := update()
	bad := update()
	bad.ID = "../unsafe"
	if _, e := s.Source(phoneA, mutation(1), []SourceUpdate{u, bad}); e == nil || len(s.Snapshot().Records) != 0 {
		t.Fatal("partial batch acknowledged")
	}
	if _, e := s.Source(phoneA, mutation(2), make([]SourceUpdate, 101)); e == nil {
		t.Fatal("oversized batch")
	}
	if _, e := s.Source("bad", mutation(2), []SourceUpdate{u}); e == nil {
		t.Fatal("invalid actor")
	}
}
func TestConcurrentFieldEditsAndReplay(t *testing.T) {
	s, _ := fixture(t)
	addWork(t, s)
	var wg sync.WaitGroup
	for n := 0; n < 20; n++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			_, e := s.Patch(phoneB, mutation(n+10), Patch{phoneA, workID, map[string]merge.Edit{"title": {Value: fmt.Sprintf("edit %d", n), Base: 1}}})
			if e != nil {
				t.Error(e)
			}
		}(n)
	}
	wg.Wait()
	r := s.Snapshot().Records[Key(phoneA, workID)]
	if len(r.Fields["title"].Variants) != 19 || len(r.Fields["title"].Changes) != 21 {
		t.Fatal("concurrent variants lost")
	}
}
func TestResumableChunksAndStableCompleteReceipt(t *testing.T) {
	s, path := fixture(t)
	data := bytes.Repeat([]byte("abcdefgh"), (ChunkSize*2+16)/8)
	begin(t, s, data)
	first := data[:ChunkSize]
	if _, e := s.Chunk(phoneA, videoID, 0, Digest(first), bytes.NewReader(first)); e != nil {
		t.Fatal(e)
	}
	s.Close()
	reopened, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	if reopened.Snapshot().Videos[Key(phoneA, videoID)].Offset != ChunkSize {
		t.Fatal("offset lost")
	}
	if _, e = reopened.Chunk(phoneA, videoID, 0, Digest(first), bytes.NewReader(first)); !errors.Is(e, ErrConflict) {
		t.Fatal("overlap accepted")
	}
	for offset := ChunkSize; offset < len(data); {
		end := min(len(data), offset+ChunkSize)
		b := data[offset:end]
		if _, e = reopened.Chunk(phoneA, videoID, int64(offset), Digest(b), bytes.NewReader(b)); e != nil {
			t.Fatal(e)
		}
		offset = end
	}
	receipt, e := reopened.Complete(phoneA, mutation(3), videoID)
	if e != nil {
		t.Fatal(e)
	}
	again, e := reopened.Complete(phoneA, mutation(3), videoID)
	if e != nil || !bytes.Equal(receipt, again) {
		t.Fatal("complete retry changed")
	}
	f, v, e := reopened.Media(phoneA, videoID)
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	raw, _ := io.ReadAll(f)
	if !v.Complete || !bytes.Equal(raw, data) {
		t.Fatal("published bytes changed")
	}
}
func TestIncompleteHiddenAndFinalHashMismatch(t *testing.T) {
	s, _ := fixture(t)
	begin(t, s, []byte("correct"))
	if _, _, e := s.Media(phoneA, videoID); !os.IsNotExist(e) {
		t.Fatal("partial video visible")
	}
	if _, e := s.Complete(phoneA, mutation(3), videoID); !errors.Is(e, ErrConflict) {
		t.Fatal("incomplete receipt")
	}
	wrong := []byte("WRONG!!")
	putChunks(t, s, wrong)
	if _, e := s.Complete(phoneA, mutation(4), videoID); e == nil {
		t.Fatal("wrong final hash acknowledged")
	}
	if s.Snapshot().Videos[Key(phoneA, videoID)].Complete {
		t.Fatal("wrong video published")
	}
}
func TestChunkValidationRetainsPending(t *testing.T) {
	for _, kind := range []string{"hash", "oversize", "length", "empty", "negative", "foreign", "path"} {
		t.Run(kind, func(t *testing.T) {
			s, _ := fixture(t)
			data := []byte("valid")
			begin(t, s, data)
			actor, id, offset, digest := phoneA, videoID, int64(0), Digest(data)
			body := data
			switch kind {
			case "hash":
				digest = Digest([]byte("wrong"))
			case "oversize":
				body = bytes.Repeat([]byte{1}, ChunkSize+1)
				digest = Digest(body)
			case "length":
				body = []byte("toolong")
				digest = Digest(body)
			case "empty":
				body = nil
				digest = Digest(nil)
			case "negative":
				offset = -1
			case "foreign":
				actor = phoneB
			case "path":
				id = "../escape"
			}
			if _, e := s.Chunk(actor, id, offset, digest, bytes.NewReader(body)); e == nil {
				t.Fatal("bad chunk accepted")
			}
			v := s.Snapshot().Videos[Key(phoneA, videoID)]
			if v.Offset != 0 || v.Complete {
				t.Fatal("bad chunk advanced manifest")
			}
		})
	}
}
func TestEveryPublicationBoundaryRecoversWithoutFalseReceipt(t *testing.T) {
	boundaries := []string{"before-write", "before-sync", "before-rename", "after-rename", "after-directory-sync"}
	for _, operation := range []string{"metadata", "chunk", "blob", "complete-state"} {
		for _, boundary := range boundaries {
			t.Run(operation+"/"+boundary, func(t *testing.T) {
				s, path := fixture(t)
				data := []byte("fixture video")
				if operation != "metadata" {
					begin(t, s, data)
				}
				if operation == "blob" || operation == "complete-state" {
					putChunks(t, s, data)
				}
				hits := 0
				s.Fault = func(point string) error {
					if point == boundary {
						hits++
						if operation != "complete-state" || hits == 2 {
							return syscall.ENOSPC
						}
					}
					return nil
				}
				var err error
				switch operation {
				case "metadata":
					_, err = s.Source(phoneA, mutation(1), []SourceUpdate{update()})
				case "chunk":
					_, err = s.Chunk(phoneA, videoID, 0, Digest(data), bytes.NewReader(data))
				default:
					_, err = s.Complete(phoneA, mutation(3), videoID)
				}
				if err == nil {
					t.Fatal("fault acknowledged success")
				}
				if e := s.Check(); e == nil {
					t.Fatal("uncertain owner continued")
				}
				s.Close()
				recovered, e := Open(path)
				if e != nil {
					t.Fatal("restart failed", e)
				}
				defer recovered.Close()
				if operation == "metadata" {
					if _, e = recovered.Source(phoneA, mutation(1), []SourceUpdate{update()}); e != nil {
						t.Fatal(e)
					}
				} else {
					v := recovered.Snapshot().Videos[Key(phoneA, videoID)]
					if v.Offset == 0 {
						putChunks(t, recovered, data)
					}
					if _, e = recovered.Complete(phoneA, mutation(3), videoID); e != nil {
						t.Fatal(e)
					}
					file, video, e := recovered.Media(phoneA, videoID)
					if e != nil {
						t.Fatal(e)
					}
					file.Close()
					if !video.Complete {
						t.Fatal("recovery not complete")
					}
				}
			})
		}
	}
}
func TestArchiveAliasingPermissionsAndVersionFailClosed(t *testing.T) {
	for _, kind := range []string{"symlink", "hardlink", "permissions", "version", "corrupt", "tampered-media"} {
		t.Run(kind, func(t *testing.T) {
			s, path := fixture(t)
			addWork(t, s)
			if kind == "tampered-media" {
				data := []byte("test")
				begin(t, s, data)
				putChunks(t, s, data)
				if _, e := s.Complete(phoneA, mutation(3), videoID); e != nil {
					t.Fatal(e)
				}
			}
			s.Close()
			target := filepath.Join(path, "state.json")
			before, _ := os.ReadFile(target)
			switch kind {
			case "symlink":
				if e := os.Symlink(target, filepath.Join(path, "alias")); e != nil {
					t.Fatal(e)
				}
			case "hardlink":
				if e := os.Link(target, filepath.Join(path, "alias")); e != nil {
					t.Fatal(e)
				}
			case "permissions":
				os.Chmod(target, 0644)
			case "version":
				os.WriteFile(target, []byte(strings.Replace(string(before), `"version":1`, `"version":99`, 1)), 0600)
			case "corrupt":
				os.WriteFile(target, []byte("corrupt"), 0600)
			case "tampered-media":
				os.WriteFile(filepath.Join(path, blobName(Key(phoneA, videoID))), []byte("evil"), 0600)
			}
			changed, _ := os.ReadFile(target)
			if reopened, e := Open(path); e == nil {
				reopened.Close()
				t.Fatal("unsafe archive accepted")
			}
			after, _ := os.ReadFile(target)
			if !bytes.Equal(changed, after) {
				t.Fatal("failed open modified data")
			}
		})
	}
}
func TestUnreferencedObjectsAndMissingStateAreRetained(t *testing.T) {
	s, path := fixture(t)
	data := []byte("source")
	begin(t, s, data)
	orphan := filepath.Join(path, chunkName(Key(phoneA, videoID), 0))
	os.WriteFile(orphan, []byte("unknown retained object"), 0600)
	if _, e := s.Chunk(phoneA, videoID, 0, Digest(data), bytes.NewReader(data)); e == nil {
		t.Fatal("unknown orphan overwritten")
	}
	raw, _ := os.ReadFile(orphan)
	if string(raw) != "unknown retained object" {
		t.Fatal("orphan lost")
	}
	s.Close()
	os.Remove(filepath.Join(path, "state.json"))
	if reopened, e := Open(path); e == nil {
		reopened.Close()
		t.Fatal("missing state reset archive")
	}
	raw, _ = os.ReadFile(orphan)
	if string(raw) != "unknown retained object" {
		t.Fatal("failed recovery altered orphan")
	}
}
func TestExclusiveOwnerAndExternalChanges(t *testing.T) {
	s, path := fixture(t)
	addWork(t, s)
	if other, e := Open(path); e == nil {
		other.Close()
		t.Fatal("second owner admitted")
	}
	target := filepath.Join(path, "state.json")
	os.WriteFile(target, []byte("external bytes"), 0600)
	if _, e := s.Patch(phoneB, mutation(2), Patch{phoneA, workID, map[string]merge.Edit{"title": {Value: "overwrite", Base: 1}}}); e == nil {
		t.Fatal("external bytes overwritten")
	}
	raw, _ := os.ReadFile(target)
	if string(raw) != "external bytes" {
		t.Fatal("external state not retained")
	}
}
func TestLocalMissingRetainsArchive(t *testing.T) {
	s, _ := fixture(t)
	data := []byte("mp4 fixture")
	begin(t, s, data)
	putChunks(t, s, data)
	if _, e := s.Complete(phoneA, mutation(3), videoID); e != nil {
		t.Fatal(e)
	}
	if _, e := s.MarkMissing(phoneA, mutation(4), videoID); e != nil {
		t.Fatal(e)
	}
	f, v, e := s.Media(phoneA, videoID)
	if e != nil {
		t.Fatal(e)
	}
	f.Close()
	if !v.Missing || !v.Complete {
		t.Fatal("missing removed archive")
	}
}
func TestStrictJSONRejectsAmbiguity(t *testing.T) {
	for _, raw := range []string{`{"version":1,"version":2}`, `{"unknown":1}`, `{} {}`, string([]byte{'"', 255, '"'}), `{"version":1.5}`} {
		var s struct {
			Version int `json:"version"`
		}
		if Strict([]byte(raw), &s) == nil {
			t.Fatalf("ambiguous JSON accepted: %q", raw)
		}
	}
}
func TestUnicodeJSONEscapesDoNotReplaceData(t *testing.T) {
	for _, raw := range []string{`"\ud800"`, `"\udfff"`, `"\ud800\u0041"`} {
		var text string
		if Strict([]byte(raw), &text) == nil {
			t.Fatal("unpaired surrogate accepted")
		}
	}
	for _, raw := range []string{`"\ud83d\ude00"`, `"\\ud800"`, `"�"`} {
		var text string
		if e := Strict([]byte(raw), &text); e != nil {
			t.Fatal("valid Unicode rejected", e)
		}
	}
}
func TestStoppedSnapshotRestoresAcknowledgedData(t *testing.T) {
	s, path := fixture(t)
	addWork(t, s)
	data := []byte("retained fixture video")
	begin(t, s, data)
	putChunks(t, s, data)
	if _, e := s.Complete(phoneA, mutation(3), videoID); e != nil {
		t.Fatal(e)
	}
	if _, e := s.Patch(phoneB, mutation(4), Patch{phoneA, workID, map[string]merge.Edit{"description": {Value: "remote description", Base: 1}}}); e != nil {
		t.Fatal(e)
	}
	s.Close()
	destination := filepath.Join(t.TempDir(), "restore")
	if e := os.Mkdir(destination, 0700); e != nil {
		t.Fatal(e)
	}
	files, e := os.ReadDir(path)
	if e != nil {
		t.Fatal(e)
	}
	for _, file := range files {
		raw, e := os.ReadFile(filepath.Join(path, file.Name()))
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(filepath.Join(destination, file.Name()), raw, 0600); e != nil {
			t.Fatal(e)
		}
	}
	restored, e := Open(destination)
	if e != nil {
		t.Fatal(e)
	}
	defer restored.Close()
	if restored.Snapshot().Records[Key(phoneA, workID)].Fields["description"].Value != "remote description" {
		t.Fatal("metadata not restored")
	}
	f, _, e := restored.Media(phoneA, videoID)
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	raw, _ := io.ReadAll(f)
	if !bytes.Equal(raw, data) {
		t.Fatal("media not restored")
	}
}
func TestLowSpaceStrictBoundaryNoQuota(t *testing.T) {
	for _, c := range []struct {
		free, total uint64
		low         bool
	}{{10, 100, false}, {9, 100, true}, {11, 100, false}, {0, 100, true}, {0, 0, false}, {1000000000000, 1000000000000, false}} {
		if LowSpace(c.free, c.total) != c.low {
			t.Fatal(c)
		}
	}
}
func TestTimingAndLinkContract(t *testing.T) {
	s := update().Source
	if !s.Valid() {
		t.Fatal("planned fixture invalid")
	}
	for _, raw := range []string{`{}`, `null`, `{"start":null,"deadline":10,"endElapsed":null,"reason":null,"confirmedElapsed":0}`, `{"start":null,"deadline":null,"endElapsed":null,"reason":null,"confirmedElapsed":-1}`, `{"unknown":true}`} {
		bad := s
		bad.Timing = json.RawMessage(raw)
		if bad.Valid() {
			t.Fatal("invalid timing accepted", raw)
		}
	}
	bad := s
	bad.Links = json.RawMessage(`null`)
	if bad.Valid() {
		t.Fatal("null links accepted")
	}
	start := Moment{"boot", 1000, 10000}
	deadline := int64(1501000)
	timing := Timing{Start: &start, Deadline: &deadline}
	s.State = "RUNNING"
	s.Timing, _ = json.Marshal(timing)
	if !s.Valid() {
		t.Fatal("running fixture invalid")
	}
	timing.Start.Elapsed = 9223372036854775800
	s.Timing, _ = json.Marshal(timing)
	if s.Valid() {
		t.Fatal("overflow accepted")
	}
}
