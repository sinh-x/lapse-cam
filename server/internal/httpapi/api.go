// Package httpapi is shared by the native TLS listener and isolated TLS fixtures.
package httpapi

import (
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"timelapse/internal/auth"
	"timelapse/internal/merge"
	"timelapse/internal/storage"
)

//go:embed web.html
var web []byte

//go:embed app.js
var script []byte

//go:embed style.css
var style []byte

type Config struct {
	Address     string `json:"address"`
	Hostname    string `json:"hostname"`
	Port        int    `json:"port"`
	Certificate string `json:"certificate"`
	Key         string `json:"key"`
	Credentials string `json:"credentials"`
	State       string `json:"state"`
}

func (c Config) Validate() error {
	ip, e := netip.ParseAddr(c.Address)
	if e != nil || (!tailnetIP(ip)) {
		return errors.New("listener must be an explicit Tailscale IP")
	}
	if !strings.HasSuffix(c.Hostname, ".ts.net") || strings.ContainsAny(c.Hostname, "/:@ \\?#") || c.Hostname != strings.ToLower(c.Hostname) {
		return errors.New("exact lowercase tailnet certificate hostname required")
	}
	if c.Port < 1024 || c.Port > 65535 || c.Certificate == "" || c.Key == "" || c.Credentials == "" || c.State == "" {
		return errors.New("non-root port, certificate, private key, credentials and state required")
	}
	return nil
}
func (c Config) Authority() string { return net.JoinHostPort(c.Hostname, strconv.Itoa(c.Port)) }

type session struct {
	CSRF     string
	Until    time.Time
	Verifier string
}
type failures []time.Time
type API struct {
	Store       *storage.Store
	Credentials func() (auth.Credentials, error)
	Authority   string
	mu          sync.Mutex
	sessions    map[string]session
	failures    map[string]failures
	Now         func() time.Time
}

func New(store *storage.Store, credentials func() (auth.Credentials, error), authority string) *API {
	return &API{Store: store, Credentials: credentials, Authority: authority, sessions: map[string]session{}, failures: map[string]failures{}, Now: time.Now}
}
func (a *API) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// Browser form POSTs need their same-origin Origin header for strict CSRF
	// checks; no-referrer turns that header into "null". Cross-origin referrers
	// remain suppressed, and foreign/null origins are still rejected below.
	w.Header().Set("Referrer-Policy", "same-origin")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
	w.Header().Set("Strict-Transport-Security", "max-age=31536000")
	if r.TLS == nil || r.Host != a.Authority || r.URL.RawQuery != "" && strings.Contains(strings.ToLower(r.URL.RawQuery), "token") {
		http.Error(w, "trusted HTTPS required", http.StatusBadRequest)
		return
	}
	// No reverse-proxy or forwarded-identity interpretation, and no CORS endpoints.
	if r.Method != "GET" && r.Method != "HEAD" && r.Header.Get("Origin") != "" && r.Header.Get("Origin") != "https://"+a.Authority {
		http.Error(w, "origin rejected", http.StatusForbidden)
		return
	}
	if r.URL.Path == "/login" {
		a.login(w, r)
		return
	}
	if r.URL.Path == "/app.js" && r.Method == "GET" {
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		w.Write(script)
		return
	}
	if r.URL.Path == "/style.css" && r.Method == "GET" {
		w.Header().Set("Content-Type", "text/css; charset=utf-8")
		w.Write(style)
		return
	}
	credentials, err := a.Credentials()
	if err != nil {
		http.Error(w, "authorization unavailable", http.StatusServiceUnavailable)
		return
	}
	actor, device := credentials.Device(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	device = device && strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ")
	browser, sess := a.browser(r, credentials)
	if !device && !browser {
		http.Error(w, "authorization required", http.StatusUnauthorized)
		return
	}
	if r.URL.Path == "/logout" {
		if !browser || r.Method != "POST" || !a.csrf(r, sess) {
			http.Error(w, "logout rejected", 403)
			return
		}
		cookie, _ := r.Cookie("archive")
		a.mu.Lock()
		delete(a.sessions, storage.Digest([]byte(cookie.Value)))
		a.mu.Unlock()
		http.SetCookie(w, &http.Cookie{Name: "archive", Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode})
		writeJSON(w, map[string]bool{"ok": true})
		return
	}
	if err := a.Store.Check(); err != nil {
		http.Error(w, "archive recovery required; bytes retained", 503)
		return
	}
	if r.URL.Path == "/" && r.Method == "GET" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(web)
		return
	}
	if r.URL.Path == "/v1/session" && r.Method == "GET" {
		writeJSON(w, map[string]string{"csrf": sess.CSRF})
		return
	}
	if r.URL.Path == "/v1/history" && r.Method == "GET" {
		a.history(w, r)
		return
	}
	if r.URL.Path == "/v1/status" && r.Method == "GET" {
		low, e := a.Store.Space()
		if e != nil {
			http.Error(w, "space status unavailable", 503)
			return
		}
		writeJSON(w, map[string]any{"lowStorage": low, "serverTime": a.Now().UnixMilli()})
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/media/") && (r.Method == "GET" || r.Method == "HEAD") {
		a.media(w, r)
		return
	}
	if !device {
		http.Error(w, "browser is read-only", http.StatusForbidden)
		return
	}
	if r.URL.Path == "/v1/identity" && r.Method == "GET" {
		writeJSON(w, map[string]string{"device": actor})
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/uploads/") {
		a.upload(w, r, actor)
		return
	}
	if r.Method != "POST" {
		http.Error(w, "not found", 404)
		return
	}
	mutation := r.Header.Get("X-Mutation-ID")
	var result json.RawMessage
	switch r.URL.Path {
	case "/v1/source":
		var updates []storage.SourceUpdate
		if !decode(w, r, &updates) {
			return
		}
		result, err = a.Store.Source(actor, mutation, updates)
	case "/v1/patch":
		var p storage.Patch
		if !decode(w, r, &p) {
			return
		}
		result, err = a.Store.Patch(actor, mutation, p)
	default:
		http.Error(w, "not found", 404)
		return
	}
	reply(w, result, err)
}
func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(value)
}
func decode(w http.ResponseWriter, r *http.Request, value any) bool {
	if r.Header.Get("Content-Type") != "application/json" {
		http.Error(w, "JSON required", 415)
		return false
	}
	raw, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 2<<20))
	if e != nil {
		http.Error(w, "metadata limit exceeded", 413)
		return false
	}
	if e = storage.Strict(raw, value); e != nil {
		http.Error(w, "invalid version-1 JSON", 400)
		return false
	}
	return true
}
func reply(w http.ResponseWriter, result json.RawMessage, err error) {
	if err != nil {
		status := 400
		if errors.Is(err, storage.ErrConflict) {
			status = 409
		}
		if errors.Is(err, os.ErrNotExist) {
			status = 404
		}
		if errors.Is(err, storage.ErrRecovery) {
			status = 503
		}
		var pathError *os.PathError
		if errors.As(err, &pathError) {
			status = 503
		}
		http.Error(w, "request not acknowledged; reconcile or retry after recovery", status)
		return
	}
	writeJSON(w, result)
}
func (a *API) csrf(r *http.Request, s session) bool {
	return r.Header.Get("Origin") == "https://"+a.Authority && subtle.ConstantTimeCompare([]byte(r.Header.Get("X-CSRF-Token")), []byte(s.CSRF)) == 1
}
func verifier(c auth.Credentials) string {
	return storage.Digest([]byte(c.Username + ":" + c.Salt + ":" + c.Password))
}
func (a *API) browser(r *http.Request, c auth.Credentials) (bool, session) {
	cookie, e := r.Cookie("archive")
	if e != nil {
		return false, session{}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	key := storage.Digest([]byte(cookie.Value))
	s, ok := a.sessions[key]
	if !ok || !a.Now().Before(s.Until) || s.Verifier != verifier(c) {
		delete(a.sessions, key)
		return false, session{}
	}
	return true, s
}
func (a *API) login(w http.ResponseWriter, r *http.Request) {
	if r.Method == "GET" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Private archive login</title><link rel="stylesheet" href="/style.css"><h1>Private archive</h1><form method="post" action="/login"><label>Username <input name="username" autocomplete="username" required maxlength="120"></label><label>Password <input name="password" type="password" autocomplete="current-password" required maxlength="1024"></label><button>Log in</button></form></html>`)
		return
	}
	if r.Method != "POST" || r.Header.Get("Origin") != "https://"+a.Authority {
		http.Error(w, "login origin rejected", 403)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 8192)
	if e := r.ParseForm(); e != nil {
		http.Error(w, "invalid login", 400)
		return
	}
	if len(r.PostForm) != 2 || len(r.PostForm["username"]) != 1 || len(r.PostForm["password"]) != 1 {
		http.Error(w, "ambiguous login", 400)
		return
	}
	c, e := a.Credentials()
	if e != nil {
		http.Error(w, "authorization unavailable", 503)
		return
	}
	source, _, e := net.SplitHostPort(r.RemoteAddr)
	if e != nil {
		http.Error(w, "invalid source", 400)
		return
	}
	// Identity is the configured account, not attacker-controlled submitted usernames.
	keys := []string{"identity:" + c.Username, "source:" + source}
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.Now()
	for k, f := range a.failures {
		for len(f) > 0 && now.Sub(f[0]) >= time.Minute {
			f = f[1:]
		}
		if len(f) == 0 {
			delete(a.failures, k)
		} else {
			a.failures[k] = f
		}
	}
	for _, k := range keys {
		if len(a.failures[k]) >= 5 {
			w.Header().Set("Retry-After", "60")
			http.Error(w, "login temporarily throttled", 429)
			return
		}
	}
	if len(r.PostForm.Get("password")) > 1024 || !c.Browser(r.PostForm.Get("username"), r.PostForm.Get("password")) {
		for _, k := range keys {
			a.failures[k] = append(a.failures[k], now)
		}
		http.Error(w, "invalid login", 401)
		return
	}
	for k, s := range a.sessions {
		if !now.Before(s.Until) {
			delete(a.sessions, k)
		}
	}
	if len(a.sessions) >= 1024 {
		http.Error(w, "session capacity reached", 503)
		return
	}
	token := auth.Random()
	a.sessions[storage.Digest([]byte(token))] = session{auth.Random(), now.Add(8 * time.Hour), verifier(c)}
	http.SetCookie(w, &http.Cookie{Name: "archive", Value: token, Path: "/", MaxAge: 8 * 60 * 60, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode})
	http.Redirect(w, r, "/", http.StatusSeeOther)
}
func (a *API) history(w http.ResponseWriter, r *http.Request) {
	q, e := url.ParseQuery(r.URL.RawQuery)
	if e != nil {
		http.Error(w, "invalid filters", 400)
		return
	}
	for k := range q {
		switch k {
		case "device", "type", "status", "since", "until", "after", "limit":
		default:
			http.Error(w, "unknown filter", 400)
			return
		}
	}
	limit := 50
	if q.Get("limit") != "" {
		limit, e = strconv.Atoi(q.Get("limit"))
		if e != nil || limit < 1 || limit > 50 {
			http.Error(w, "page limit 1–50", 400)
			return
		}
	}
	var since, until int64
	for _, p := range []struct {
		name  string
		value *int64
	}{{"since", &since}, {"until", &until}} {
		if q.Get(p.name) != "" {
			*p.value, e = strconv.ParseInt(q.Get(p.name), 10, 64)
			if e != nil || *p.value < 0 {
				http.Error(w, "invalid date filter", 400)
				return
			}
		}
	}
	st := a.Store.Snapshot()
	keys := []string{}
	for k, rec := range st.Records {
		status := rec.Source.State
		if rec.Source.Archived {
			status = "ARCHIVED"
		}
		if rec.Source.Deleted {
			status = "DELETED"
		}
		if k > q.Get("after") && (q.Get("device") == "" || rec.Device == q.Get("device")) && (q.Get("type") == "" || rec.Fields["type"].Value == q.Get("type")) && (q.Get("status") == "" || status == q.Get("status")) && (since == 0 || rec.Source.CreatedAt >= since) && (until == 0 || rec.Source.CreatedAt <= until) {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	next := ""
	if len(keys) > limit {
		keys = keys[:limit]
		next = keys[len(keys)-1]
	}
	records := []storage.Record{}
	for _, k := range keys {
		records = append(records, st.Records[k])
	}
	// Videos have their own cursor, so video-only history is bounded too.
	videoKeys := []string{}
	for k, v := range st.Videos {
		if v.Complete && k > q.Get("after") && (q.Get("device") == "" || v.Device == q.Get("device")) && (since == 0 || v.SyncedAt >= since) && (until == 0 || v.SyncedAt <= until) {
			videoKeys = append(videoKeys, k)
		}
	}
	sort.Strings(videoKeys)
	// A shared cursor must advance to the earlier page boundary to never skip either set.
	if len(videoKeys) > limit {
		videoKeys = videoKeys[:limit]
		if next == "" || videoKeys[len(videoKeys)-1] < next {
			next = videoKeys[len(videoKeys)-1]
		}
	}
	if next != "" {
		for len(records) > 0 && storage.Key(records[len(records)-1].Device, records[len(records)-1].ID) > next {
			records = records[:len(records)-1]
		}
		for len(videoKeys) > 0 && videoKeys[len(videoKeys)-1] > next {
			videoKeys = videoKeys[:len(videoKeys)-1]
		}
	}
	videos := []storage.Video{}
	for _, k := range videoKeys {
		videos = append(videos, st.Videos[k])
	}
	writeJSON(w, map[string]any{"records": records, "videos": videos, "next": next})
}
func (a *API) upload(w http.ResponseWriter, r *http.Request, actor string) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/uploads/"), "/")
	if len(parts) < 1 || len(parts) > 2 || !merge.ID(parts[0]) {
		http.Error(w, "invalid upload ID", 400)
		return
	}
	id := parts[0]
	if len(parts) == 1 && r.Method == "GET" {
		v, ok := a.Store.Snapshot().Videos[storage.Key(actor, id)]
		if !ok {
			http.Error(w, "not found", 404)
			return
		}
		writeJSON(w, v)
		return
	}
	var raw json.RawMessage
	var err error
	if len(parts) == 1 && r.Method == "POST" {
		var request struct {
			Size      int64  `json:"size"`
			SHA256    string `json:"sha256"`
			Recording string `json:"recording"`
		}
		if !decode(w, r, &request) {
			return
		}
		raw, err = a.Store.Begin(actor, r.Header.Get("X-Mutation-ID"), storage.Video{ID: id, Size: request.Size, SHA256: request.SHA256, Recording: request.Recording})
	} else if len(parts) == 2 && parts[1] == "chunk" && r.Method == "PUT" {
		offset, e := strconv.ParseInt(r.Header.Get("X-Offset"), 10, 64)
		if e != nil {
			http.Error(w, "offset required", 400)
			return
		}
		var v storage.Video
		v, err = a.Store.Chunk(actor, id, offset, r.Header.Get("X-Chunk-SHA256"), http.MaxBytesReader(w, r.Body, storage.ChunkSize+1))
		if err == nil {
			writeJSON(w, v)
			return
		}
	} else if len(parts) == 2 && parts[1] == "complete" && r.Method == "POST" {
		raw, err = a.Store.Complete(actor, r.Header.Get("X-Mutation-ID"), id)
	} else if len(parts) == 2 && parts[1] == "missing" && r.Method == "POST" {
		raw, err = a.Store.MarkMissing(actor, r.Header.Get("X-Mutation-ID"), id)
	} else {
		http.Error(w, "not found", 404)
		return
	}
	reply(w, raw, err)
}
func (a *API) media(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/media/"), "/")
	if len(parts) != 2 {
		http.Error(w, "not found", 404)
		return
	}
	f, v, e := a.Store.Media(parts[0], parts[1])
	if e != nil {
		http.Error(w, "complete media not found", 404)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Content-Disposition", `inline; filename="`+v.ID+`.mp4"`)
	w.Header().Set("ETag", `"`+v.SHA256+`"`)
	http.ServeContent(w, r, v.ID+".mp4", time.UnixMilli(v.SyncedAt), f)
}

// TokenDigest exists for native provisioning; tokens themselves never enter JSON logs.
func TokenDigest(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:])
}
