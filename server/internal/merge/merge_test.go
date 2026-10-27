package merge

import (
	"strings"
	"testing"
)

func TestValidation(t *testing.T) {
	for _, c := range []struct {
		name, value string
		ok          bool
	}{
		{"title", "", false}, {"title", " \t", false}, {"title", strings.Repeat("😀", 120), true}, {"title", strings.Repeat("😀", 121), false},
		{"description", strings.Repeat("😀", 2000), true}, {"expectedOutcome", strings.Repeat("x", 2001), false}, {"type", "work", true}, {"type", "invented", false}, {"deadline", "10", false}, {"description", string([]byte{255}), false}, {"title", "<script>alert(1)</script>", true},
	} {
		if Valid(c.name, c.value) != c.ok {
			t.Fatalf("validation: %q", c.name)
		}
	}
}
func TestDifferentFieldsCommute(t *testing.T) {
	for _, order := range [][]string{{"title", "description"}, {"description", "title"}} {
		fields := map[string]Field{"title": New("original"), "description": New("")}
		for _, n := range order {
			f, e := Apply(n, fields[n], Edit{Value: "new " + n, Base: 1}, "phone", "mutation")
			if e != nil {
				t.Fatal(e)
			}
			fields[n] = f
		}
		for n, f := range fields {
			if f.Value != "new "+n || len(f.Variants) != 0 {
				t.Fatal("different fields conflicted")
			}
		}
	}
}
func TestConflictPreservesVariantsAndProvenance(t *testing.T) {
	original := New("base")
	a, e := Apply("title", original, Edit{Value: "A", Base: 1}, "A", "mutation-A")
	if e != nil {
		t.Fatal(e)
	}
	b, e := Apply("title", a, Edit{Value: "B", Base: 1}, "B", "mutation-B")
	if e != nil {
		t.Fatal(e)
	}
	c, e := Apply("title", b, Edit{Value: "C", Base: 1}, "C", "mutation-C")
	if e != nil {
		t.Fatal(e)
	}
	if original.Value != "base" || len(original.History) != 1 || a.Revision != 2 || b.Revision != 3 || c.Revision != 4 || c.Value != "A" || len(c.Variants) != 2 {
		t.Fatal("lost value or mutated input")
	}
	if c.Variants[0].BaseValue != "base" || c.Variants[0].Device != "B" || c.Changes[0].Device != "A" || c.History[1] != "base" {
		t.Fatal("lost provenance")
	}
}
func TestEqualValuesCoalesce(t *testing.T) {
	a, _ := Apply("title", New("base"), Edit{Value: "same", Base: 1}, "A", "a")
	b, e := Apply("title", a, Edit{Value: "same", Base: 1}, "B", "b")
	if e != nil || b.Revision != a.Revision || len(b.Variants) != 0 {
		t.Fatal("equal stale edit conflicted")
	}
	c, _ := Apply("title", b, Edit{Value: "different", Base: 1}, "C", "c")
	d, e := Apply("title", c, Edit{Value: "different", Base: 1}, "D", "d")
	if e != nil || len(d.Variants) != 1 || d.Revision != c.Revision {
		t.Fatal("duplicate proposal")
	}
}
func TestExplicitCASResolution(t *testing.T) {
	a, _ := Apply("title", New("base"), Edit{Value: "A", Base: 1}, "A", "a")
	b, _ := Apply("title", a, Edit{Value: "B", Base: 1}, "B", "b")
	if _, e := Apply("title", b, Edit{Value: "B", Base: 2, Resolve: true}, "A", "c"); e == nil {
		t.Fatal("stale resolution erased conflict")
	}
	c, e := Apply("title", b, Edit{Value: "chosen", Base: b.Revision, Resolve: true}, "A", "d")
	if e != nil || c.Value != "chosen" || len(c.Variants) != 0 || len(c.Changes) != 3 {
		t.Fatal("resolution failed")
	}
	if _, e = Apply("title", c, Edit{Value: "late", Base: b.Revision, Resolve: true}, "B", "e"); e == nil {
		t.Fatal("stale resolution accepted")
	}
}
func TestUnresolvedFreshEditIsStillConflict(t *testing.T) {
	a, _ := Apply("title", New("base"), Edit{Value: "A", Base: 1}, "A", "a")
	b, _ := Apply("title", a, Edit{Value: "B", Base: 1}, "B", "b")
	c, e := Apply("title", b, Edit{Value: "C", Base: b.Revision}, "C", "c")
	if e != nil || c.Value != "A" || len(c.Variants) != 2 {
		t.Fatal("implicit resolution")
	}
}
func TestUnknownBaseAndProtectedFieldsRejected(t *testing.T) {
	for _, c := range []struct {
		name string
		e    Edit
	}{{"title", Edit{Value: "new", Base: 0}}, {"state", Edit{Value: "ENDED", Base: 1}}, {"title", Edit{Value: "", Base: 1}}} {
		if _, e := Apply(c.name, New("old"), c.e, "A", "a"); e == nil {
			t.Fatal("invalid edit accepted")
		}
	}
}
func FuzzMergeNeverLosesAcceptedValue(f *testing.F) {
	f.Add("first", "second")
	f.Fuzz(func(t *testing.T, a, b string) {
		if !Valid("title", a) || !Valid("title", b) {
			return
		}
		first, e := Apply("title", New("base"), Edit{Value: a, Base: 1}, "A", "a")
		if e != nil {
			t.Fatal(e)
		}
		second, e := Apply("title", first, Edit{Value: b, Base: 1}, "B", "b")
		if e != nil {
			t.Fatal(e)
		}
		if a != "base" && a != b && second.Value != a {
			t.Fatal("lost acknowledged value")
		}
	})
}
