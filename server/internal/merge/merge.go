// Package merge implements field-revision, not wall-clock, concurrency control.
package merge

import (
	"errors"
	"math"
	"regexp"
	"strings"
	"unicode/utf8"
)

var idPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func ID(s string) bool { return idPattern.MatchString(s) }
func Valid(name, value string) bool {
	if !utf8.ValidString(value) {
		return false
	}
	switch name {
	case "title":
		return strings.TrimSpace(value) != "" && utf8.RuneCountInString(value) <= 120
	case "description", "expectedOutcome":
		return utf8.RuneCountInString(value) <= 2000
	case "type":
		return value == "work" || value == "study" || value == "personal" || value == "social" || value == "to-be-sorted"
	}
	return false
}

type Variant struct {
	Value     string `json:"value"`
	Base      uint64 `json:"base"`
	BaseValue string `json:"baseValue"`
	Device    string `json:"device"`
	Mutation  string `json:"mutation"`
}
type Field struct {
	Value    string            `json:"value"`
	Revision uint64            `json:"revision"`
	History  map[uint64]string `json:"history"`
	Variants []Variant         `json:"variants"`
	Changes  []Variant         `json:"changes"`
}
type Edit struct {
	Value   string `json:"value"`
	Base    uint64 `json:"base"`
	Resolve bool   `json:"resolve"`
}

func ValidField(name string, f Field) bool {
	if f.Revision < 1 || uint64(len(f.History)) != f.Revision || uint64(len(f.Changes)) != f.Revision || f.History[f.Revision] != f.Value || !Valid(name, f.Value) {
		return false
	}
	for rev, value := range f.History {
		if rev < 1 || rev > f.Revision || !Valid(name, value) {
			return false
		}
	}
	for _, v := range f.Variants {
		if !Valid(name, v.Value) || !ID(v.Device) || !ID(v.Mutation) || v.Base < 1 || f.History[v.Base] != v.BaseValue {
			return false
		}
	}
	for index, v := range f.Changes {
		if !Valid(name, v.Value) || !ID(v.Device) || !ID(v.Mutation) {
			return false
		}
		if index == 0 {
			if v.Base != 0 {
				return false
			}
		} else {
			base, ok := f.History[v.Base]
			if !ok || v.Base >= uint64(index+1) || base != v.BaseValue {
				return false
			}
		}
	}
	return true
}
func New(value string) Field {
	return Field{Value: value, Revision: 1, History: map[uint64]string{1: value}, Variants: []Variant{}, Changes: []Variant{}}
}

// Apply preserves the accepted value and every unequal stale proposal. Explicit resolution
// must compare against the exact current conflict revision. Equal retries coalesce.
func Apply(name string, f Field, edit Edit, device, mutation string) (Field, error) {
	if f.Revision == math.MaxUint64 {
		return f, errors.New("field revision exhausted")
	}
	if !Valid(name, edit.Value) {
		return f, errors.New("invalid descriptive field")
	}
	baseValue, ok := f.History[edit.Base]
	if !ok {
		return f, errors.New("unknown field base revision")
	}
	if edit.Resolve && edit.Base != f.Revision {
		return f, errors.New("stale resolution; refresh conflict")
	}
	next := f
	next.History = make(map[uint64]string, len(f.History)+1)
	for k, v := range f.History {
		next.History[k] = v
	}
	next.Variants = append([]Variant{}, f.Variants...)
	if edit.Resolve {
		next.Value = edit.Value
		next.Variants = []Variant{}
	} else if edit.Value == f.Value {
		return f, nil
	} else if edit.Base == f.Revision && len(f.Variants) == 0 {
		next.Value = edit.Value
	} else {
		for _, v := range f.Variants {
			if v.Value == edit.Value {
				return f, nil
			}
		}
		next.Variants = append(next.Variants, Variant{edit.Value, edit.Base, baseValue, device, mutation})
	}
	next.Changes = append(append([]Variant{}, f.Changes...), Variant{edit.Value, edit.Base, baseValue, device, mutation})
	next.Revision++
	next.History[next.Revision] = next.Value
	return next, nil
}
