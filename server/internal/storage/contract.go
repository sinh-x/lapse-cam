package storage

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"timelapse/internal/merge"
	"unicode/utf8"
)

type Moment struct {
	Boot    string `json:"boot"`
	Elapsed int64  `json:"elapsed"`
	Epoch   int64  `json:"epoch"`
}

func (m Moment) valid() bool {
	return strings.TrimSpace(m.Boot) != "" && len(m.Boot) <= 200 && m.Elapsed >= 0 && m.Epoch >= 0
}

type Timing struct {
	Start            *Moment `json:"start"`
	Deadline         *int64  `json:"deadline"`
	EndElapsed       *int64  `json:"endElapsed"`
	Reason           *string `json:"reason"`
	ConfirmedElapsed int64   `json:"confirmedElapsed"`
}
type Link struct {
	ID           string `json:"id"`
	WorkID       string `json:"workId"`
	RecordingID  string `json:"recordingId"`
	SpanID       string `json:"spanId"`
	Boot         string `json:"boot"`
	StartElapsed int64  `json:"startElapsed"`
	EndElapsed   int64  `json:"endElapsed"`
	StartEpoch   int64  `json:"startEpoch"`
	EndEpoch     int64  `json:"endEpoch"`
	UnknownTail  bool   `json:"unknownTail"`
	Open         bool   `json:"open"`
}

var recordingPattern = regexp.MustCompile(`^[0-9]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func (s Source) validTimingAndLinks() bool {
	var t Timing
	var links []Link
	if Strict(s.Timing, &t) != nil || Strict(s.Links, &links) != nil || links == nil || len(links) > 10000 {
		return false
	}
	duration := int64(s.PlannedMinutes) * 60000
	if t.ConfirmedElapsed < 0 || t.ConfirmedElapsed > duration {
		return false
	}
	if s.State == "PLANNED" {
		if t.Start != nil || t.Deadline != nil || t.EndElapsed != nil || t.Reason != nil || t.ConfirmedElapsed != 0 || len(links) != 0 {
			return false
		}
	} else {
		if t.Start == nil || !t.Start.valid() || t.Deadline == nil || t.Start.Elapsed > math.MaxInt64-duration || *t.Deadline != t.Start.Elapsed+duration {
			return false
		}
		if s.State == "RUNNING" {
			if t.EndElapsed != nil || t.Reason != nil {
				return false
			}
		} else {
			if t.EndElapsed == nil || t.Reason == nil || *t.EndElapsed < t.Start.Elapsed || *t.EndElapsed > *t.Deadline || t.ConfirmedElapsed != *t.EndElapsed-t.Start.Elapsed {
				return false
			}
			if *t.Reason != "USER" && *t.Reason != "EXPIRY" && *t.Reason != "REBOOT" {
				return false
			}
			if *t.Reason == "EXPIRY" && *t.EndElapsed != *t.Deadline {
				return false
			}
		}
	}
	seen := map[string]bool{}
	for _, l := range links {
		if !merge.ID(l.WorkID) || !merge.ID(l.SpanID) || l.ID != l.WorkID+":"+l.SpanID || !recordingPattern.MatchString(l.RecordingID) || seen[l.ID] || strings.TrimSpace(l.Boot) == "" || len(l.Boot) > 200 || l.StartElapsed < 0 || l.EndElapsed <= l.StartElapsed || l.StartEpoch < 0 || l.EndEpoch <= l.StartEpoch || l.EndEpoch-l.StartEpoch != l.EndElapsed-l.StartElapsed {
			return false
		}
		if t.Start == nil || t.Start.Boot != l.Boot || l.StartElapsed < t.Start.Elapsed || l.EndElapsed > *t.Deadline || (t.EndElapsed != nil && l.EndElapsed > *t.EndElapsed) {
			return false
		}
		seen[l.ID] = true
	}
	return true
}
func (s Source) linksBelongTo(id string) bool {
	var links []Link
	if Strict(s.Links, &links) != nil {
		return false
	}
	for _, l := range links {
		if l.WorkID != id {
			return false
		}
	}
	return true
}

// Require every versioned property explicitly, including nullable clocks and flags.
func requiredFields(raw []byte, target any) error {
	var data any
	if err := json.Unmarshal(raw, &data); err != nil {
		return err
	}
	var check func(any, reflect.Type) error
	check = func(value any, t reflect.Type) error {
		if t.Kind() == reflect.Pointer {
			if value == nil {
				return nil
			}
			return check(value, t.Elem())
		}
		switch t.Kind() {
		case reflect.Struct:
			object, ok := value.(map[string]any)
			if !ok {
				return errors.New("JSON object required")
			}
			for i := 0; i < t.NumField(); i++ {
				field := t.Field(i)
				if !field.IsExported() {
					continue
				}
				name := strings.Split(field.Tag.Get("json"), ",")[0]
				if name == "-" {
					continue
				}
				if name == "" {
					name = field.Name
				}
				child, exists := object[name]
				if !exists {
					return errors.New("missing versioned property: " + name)
				}
				if err := check(child, field.Type); err != nil {
					return err
				}
			}
		case reflect.Slice:
			if t.Elem().Kind() == reflect.Uint8 {
				return nil
			}
			if list, ok := value.([]any); ok {
				for _, child := range list {
					if err := check(child, t.Elem()); err != nil {
						return err
					}
				}
			}
		case reflect.Map:
			if object, ok := value.(map[string]any); ok {
				for _, child := range object {
					if err := check(child, t.Elem()); err != nil {
						return err
					}
				}
			}
		default:
			if value == nil {
				return errors.New("null scalar rejected")
			}
		}
		return nil
	}
	return check(data, reflect.TypeOf(target).Elem())
}

// encoding/json otherwise accepts duplicate names and replaces invalid UTF-8.
func uniqueJSON(raw []byte) error {
	if !utf8.Valid(raw) {
		return errors.New("invalid UTF-8 JSON")
	}
	for i := 0; i < len(raw); i++ {
		if raw[i] != '\\' {
			continue
		}
		i++
		if i >= len(raw) || raw[i] != 'u' {
			continue
		}
		if i+4 >= len(raw) {
			return errors.New("invalid Unicode escape")
		}
		code, e := strconv.ParseUint(string(raw[i+1:i+5]), 16, 16)
		if e != nil {
			return e
		}
		i += 4
		if code >= 0xdc00 && code <= 0xdfff {
			return errors.New("unpaired low surrogate")
		}
		if code >= 0xd800 && code <= 0xdbff {
			if i+6 >= len(raw) || raw[i+1] != '\\' || raw[i+2] != 'u' {
				return errors.New("unpaired high surrogate")
			}
			low, e := strconv.ParseUint(string(raw[i+3:i+7]), 16, 16)
			if e != nil || low < 0xdc00 || low > 0xdfff {
				return errors.New("invalid surrogate pair")
			}
			i += 6
		}
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	var value func() error
	value = func() error {
		token, e := d.Token()
		if e != nil {
			return e
		}
		if delim, ok := token.(json.Delim); ok {
			switch delim {
			case '{':
				seen := map[string]bool{}
				for d.More() {
					key, e := d.Token()
					if e != nil {
						return e
					}
					name, ok := key.(string)
					if !ok || seen[name] {
						return errors.New("duplicate or invalid JSON key")
					}
					seen[name] = true
					if e = value(); e != nil {
						return e
					}
				}
				end, e := d.Token()
				if e != nil || end != json.Delim('}') {
					return errors.New("invalid object")
				}
			case '[':
				for d.More() {
					if e = value(); e != nil {
						return e
					}
				}
				end, e := d.Token()
				if e != nil || end != json.Delim(']') {
					return errors.New("invalid array")
				}
			default:
				return errors.New("unexpected delimiter")
			}
		}
		return nil
	}
	if e := value(); e != nil {
		return e
	}
	if _, e := d.Token(); e != io.EOF {
		return errors.New("trailing JSON")
	}
	return nil
}
