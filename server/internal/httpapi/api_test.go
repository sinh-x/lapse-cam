package httpapi

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
	"timelapse/internal/auth"
	"timelapse/internal/merge"
	"timelapse/internal/storage"
)

const deviceA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const deviceB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const recordID = "11111111-1111-4111-8111-111111111111"
const exportID = "22222222-2222-4222-8222-222222222222"

func mid(n int) string { return fmt.Sprintf("00000000-0000-4000-8000-%012x", n) }

var credOnce sync.Once
var baseCredentials auth.Credentials
var tokenA = auth.Random()
var tokenB = auth.Random()

type fixture struct {
	api         *API
	server      *httptest.Server
	client      *http.Client
	credentials *auth.Credentials
}

func setup(t *testing.T) *fixture {
	t.Helper()
	credOnce.Do(func() {
		var e error
		baseCredentials, e = auth.Init("owner", "a long fixture password")
		if e != nil {
			panic(e)
		}
		baseCredentials.Devices[deviceA] = auth.Device{Name: "Pixel", Hash: TokenDigest(tokenA)}
		baseCredentials.Devices[deviceB] = auth.Device{Name: "OPPO", Hash: TokenDigest(tokenB)}
	})
	raw, _ := json.Marshal(baseCredentials)
	var credentials auth.Credentials
	json.Unmarshal(raw, &credentials)
	store, e := storage.Open(filepath.Join(t.TempDir(), "archive"))
	if e != nil {
		t.Fatal(e)
	}
	api := New(store, func() (auth.Credentials, error) { return credentials, nil }, "")
	server := httptest.NewUnstartedServer(api)
	server.Config.ErrorLog = log.New(io.Discard, "", 0)
	server.StartTLS()
	u, _ := url.Parse(server.URL)
	api.Authority = u.Host
	client := server.Client()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	t.Cleanup(func() { server.Close(); store.Close() })
	return &fixture{api, server, client, &credentials}
}
func (f *fixture) request(t *testing.T, method, path, token string, body any, headers map[string]string) *http.Response {
	t.Helper()
	var reader io.Reader
	if raw, ok := body.([]byte); ok {
		reader = bytes.NewReader(raw)
	} else if body != nil {
		raw, e := json.Marshal(body)
		if e != nil {
			t.Fatal(e)
		}
		reader = bytes.NewReader(raw)
	}
	r, e := http.NewRequest(method, f.server.URL+path, reader)
	if e != nil {
		t.Fatal(e)
	}
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	response, e := f.client.Do(r)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { response.Body.Close() })
	return response
}
func requireStatus(t *testing.T, r *http.Response, status int) {
	t.Helper()
	if r.StatusCode != status {
		raw, _ := io.ReadAll(r.Body)
		t.Fatalf("status %d expected %d: %s", r.StatusCode, status, raw)
	}
}
func sourceUpdate(id string, created int64) storage.SourceUpdate {
	return storage.SourceUpdate{ID: id, Sequence: 1, Source: storage.Source{CreatedAt: created, PlannedMinutes: 25, State: "PLANNED", Timing: json.RawMessage(`{"start":null,"deadline":null,"endElapsed":null,"reason":null,"confirmedElapsed":0}`), Links: json.RawMessage(`[]`)}, Initial: map[string]string{"title": "<script>not executable</script>", "type": "work", "description": "", "expectedOutcome": ""}}
}
func (f *fixture) add(t *testing.T) {
	t.Helper()
	requireStatus(t, f.request(t, "POST", "/v1/source", tokenA, []storage.SourceUpdate{sourceUpdate(recordID, 100)}, map[string]string{"X-Mutation-ID": mid(1)}), 200)
}
func (f *fixture) video(t *testing.T) {
	t.Helper()
	data := []byte("fixture MP4 bytes")
	requireStatus(t, f.request(t, "POST", "/v1/uploads/"+exportID, tokenA, map[string]any{"size": len(data), "sha256": storage.Digest(data), "recording": "100-" + recordID}, map[string]string{"X-Mutation-ID": mid(2)}), 200)
	requireStatus(t, f.request(t, "PUT", "/v1/uploads/"+exportID+"/chunk", tokenA, data, map[string]string{"X-Offset": "0", "X-Chunk-SHA256": storage.Digest(data)}), 200)
	requireStatus(t, f.request(t, "POST", "/v1/uploads/"+exportID+"/complete", tokenA, nil, map[string]string{"X-Mutation-ID": mid(3)}), 200)
}
func (f *fixture) login(t *testing.T) *http.Response {
	t.Helper()
	jar, e := cookiejar.New(nil)
	if e != nil {
		t.Fatal(e)
	}
	f.client.Jar = jar
	form := url.Values{"username": {"owner"}, "password": {"a long fixture password"}}
	return f.request(t, "POST", "/login", "", []byte(form.Encode()), map[string]string{"Content-Type": "application/x-www-form-urlencoded", "Origin": f.server.URL})
}
func TestAllPrivateRoutesRequireAuthorization(t *testing.T) {
	f := setup(t)
	for _, path := range []string{"/", "/v1/history", "/v1/status", "/v1/media/" + deviceA + "/" + exportID, "/v1/uploads/" + exportID, "/v1/source", "/v1/patch"} {
		requireStatus(t, f.request(t, "GET", path, "", nil, nil), 401)
	}
}
func TestDeviceRevocationAndScopedUpload(t *testing.T) {
	f := setup(t)
	f.add(t)
	f.video(t)
	d := f.credentials.Devices[deviceA]
	d.Revoked = true
	f.credentials.Devices[deviceA] = d
	requireStatus(t, f.request(t, "GET", "/v1/history", tokenA, nil, nil), 401)
	requireStatus(t, f.request(t, "GET", "/v1/history", tokenB, nil, nil), 200)
	requireStatus(t, f.request(t, "GET", "/v1/uploads/"+exportID, tokenB, nil, nil), 404)
	requireStatus(t, f.request(t, "POST", "/v1/uploads/"+exportID+"/complete", tokenB, nil, map[string]string{"X-Mutation-ID": mid(4)}), 404)
}
func TestTrustedTLSOnlyAndHostOriginChecks(t *testing.T) {
	f := setup(t)
	// A normal client without the fixture CA rejects the server chain; no InsecureSkipVerify.
	client := &http.Client{Timeout: 5 * time.Second}
	if r, e := client.Get(f.server.URL + "/login"); e == nil {
		r.Body.Close()
		t.Fatal("untrusted TLS accepted")
	}
	r := httptest.NewRequest("GET", "http://"+f.api.Authority+"/v1/history", nil)
	r.Header.Set("Authorization", "Bearer "+tokenA)
	rec := httptest.NewRecorder()
	f.api.ServeHTTP(rec, r)
	if rec.Code != 400 {
		t.Fatal("cleartext accepted")
	}
	r = httptest.NewRequest("GET", "https://wrong.example/v1/history", nil)
	r.TLS = &tls.ConnectionState{}
	rec = httptest.NewRecorder()
	f.api.ServeHTTP(rec, r)
	if rec.Code != 400 {
		t.Fatal("wrong Host accepted")
	}
	requireStatus(t, f.request(t, "POST", "/v1/source", tokenA, []storage.SourceUpdate{sourceUpdate(recordID, 100)}, map[string]string{"Origin": "https://evil.example", "X-Mutation-ID": mid(1)}), 403)
}
func TestBrowserLoginFlagsReadOnlyCSRFAndLogout(t *testing.T) {
	f := setup(t)
	f.add(t)
	r := f.login(t)
	requireStatus(t, r, 303)
	cookies := r.Cookies()
	if len(cookies) != 1 || !cookies[0].Secure || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteStrictMode || cookies[0].Path != "/" {
		t.Fatal("unsafe browser cookie")
	}
	requireStatus(t, f.request(t, "GET", "/v1/history", "", nil, nil), 200)
	requireStatus(t, f.request(t, "POST", "/v1/patch", "", map[string]any{}, map[string]string{"Origin": f.server.URL}), 403)
	requireStatus(t, f.request(t, "POST", "/logout", "", nil, map[string]string{"Origin": f.server.URL}), 403)
	var session map[string]string
	json.NewDecoder(f.request(t, "GET", "/v1/session", "", nil, nil).Body).Decode(&session)
	requireStatus(t, f.request(t, "POST", "/logout", "", nil, map[string]string{"Origin": f.server.URL, "X-CSRF-Token": session["csrf"]}), 200)
	// Replaying the original cookie fails even after a valid logout.
	req, _ := http.NewRequest("GET", f.server.URL+"/v1/history", nil)
	req.AddCookie(cookies[0])
	response, e := f.client.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	requireStatus(t, response, 401)
}
func TestBrowserExpirationAndPasswordRotationInvalidateSession(t *testing.T) {
	for _, kind := range []string{"expiry", "password"} {
		t.Run(kind, func(t *testing.T) {
			f := setup(t)
			requireStatus(t, f.login(t), 303)
			if kind == "expiry" {
				future := time.Now().Add(9 * time.Hour)
				f.api.Now = func() time.Time { return future }
			} else {
				f.credentials.Password = TokenDigest("new verifier")
			}
			requireStatus(t, f.request(t, "GET", "/v1/history", "", nil, nil), 401)
		})
	}
}
func TestLoginThrottlesFiveFailuresAcrossSubmittedUsernames(t *testing.T) {
	f := setup(t)
	for n := 0; n < 5; n++ {
		form := url.Values{"username": {fmt.Sprint(n)}, "password": {"wrong"}}
		requireStatus(t, f.request(t, "POST", "/login", "", []byte(form.Encode()), map[string]string{"Content-Type": "application/x-www-form-urlencoded", "Origin": f.server.URL}), 401)
	}
	requireStatus(t, f.login(t), 429)
	future := time.Now().Add(61 * time.Second)
	f.api.Now = func() time.Time { return future }
	requireStatus(t, f.login(t), 303)
}
func TestLoginLimitUsesRollingMinute(t *testing.T) {
	f := setup(t)
	base := time.Now()
	now := base
	f.api.Now = func() time.Time { return now }
	wrong := func() *http.Response {
		return f.request(t, "POST", "/login", "", []byte("username=owner&password=wrong"), map[string]string{"Content-Type": "application/x-www-form-urlencoded", "Origin": f.server.URL})
	}
	requireStatus(t, wrong(), 401)
	now = base.Add(59 * time.Second)
	for n := 0; n < 4; n++ {
		requireStatus(t, wrong(), 401)
	}
	now = base.Add(61 * time.Second)
	requireStatus(t, wrong(), 401)
	requireStatus(t, wrong(), 429)
}
func TestLoginNeedsExactOrigin(t *testing.T) {
	f := setup(t)
	for _, origin := range []string{"", "https://evil.example"} {
		requireStatus(t, f.request(t, "POST", "/login", "", []byte("username=owner&password=wrong"), map[string]string{"Content-Type": "application/x-www-form-urlencoded", "Origin": origin}), 403)
	}
}
func TestRangePlaybackAndMissingSourceRetention(t *testing.T) {
	f := setup(t)
	f.video(t)
	path := "/v1/media/" + deviceA + "/" + exportID
	r := f.request(t, "GET", path, tokenB, nil, map[string]string{"Range": "bytes=2-6"})
	requireStatus(t, r, 206)
	raw, _ := io.ReadAll(r.Body)
	if string(raw) != "xture" || r.Header.Get("Content-Type") != "video/mp4" || !strings.HasPrefix(r.Header.Get("Content-Disposition"), "inline;") {
		t.Fatal("range bytes/headers")
	}
	requireStatus(t, f.request(t, "HEAD", path, tokenA, nil, nil), 200)
	requireStatus(t, f.request(t, "GET", path, tokenA, nil, map[string]string{"Range": "bytes=900-999"}), 416)
	requireStatus(t, f.request(t, "POST", "/v1/uploads/"+exportID+"/missing", tokenA, nil, map[string]string{"X-Mutation-ID": mid(4)}), 200)
	requireStatus(t, f.request(t, "GET", path, tokenB, nil, nil), 200)
}
func TestFieldMergeProtectedRoutesAndUnicodeValidation(t *testing.T) {
	f := setup(t)
	f.add(t)
	for n, fields := range []map[string]merge.Edit{{"title": {Value: "A", Base: 1}}, {"description": {Value: "B", Base: 1}}, {"title": {Value: "C", Base: 1}}} {
		requireStatus(t, f.request(t, "POST", "/v1/patch", tokenB, storage.Patch{Device: deviceA, ID: recordID, Fields: fields}, map[string]string{"X-Mutation-ID": mid(n + 10)}), 200)
	}
	r := f.api.Store.Snapshot().Records[storage.Key(deviceA, recordID)]
	if r.Fields["description"].Value != "B" || r.Fields["title"].Value != "A" || len(r.Fields["title"].Variants) != 1 {
		t.Fatal("merge contract")
	}
	for n, fields := range []map[string]merge.Edit{{"state": {Value: "RUNNING", Base: 1}}, {"title": {Value: strings.Repeat("😀", 121), Base: 1}}, {"type": {Value: "invented", Base: 1}}} {
		requireStatus(t, f.request(t, "POST", "/v1/patch", tokenB, storage.Patch{Device: deviceA, ID: recordID, Fields: fields}, map[string]string{"X-Mutation-ID": mid(n + 20)}), 400)
	}
	requireStatus(t, f.request(t, "POST", "/v1/patch", tokenB, []byte(`{"device":"`+deviceA+`","id":"`+recordID+`","fields":{},"state":"RUNNING"}`), map[string]string{"X-Mutation-ID": mid(30)}), 400)
}
func TestRequestBoundsAmbiguousJSONAndUnsafeIDs(t *testing.T) {
	f := setup(t)
	for _, raw := range []string{`{"fields":{},"fields":{}}`, `{} {}`, `{"unknown":true}`} {
		requireStatus(t, f.request(t, "POST", "/v1/patch", tokenA, []byte(raw), map[string]string{"X-Mutation-ID": mid(1)}), 400)
	}
	requireStatus(t, f.request(t, "POST", "/v1/source", tokenA, bytes.Repeat([]byte{' '}, (2<<20)+1), nil), 413)
	requireStatus(t, f.request(t, "POST", "/v1/uploads/not-a-uuid", tokenA, nil, nil), 400)
	requireStatus(t, f.request(t, "GET", "/v1/history?limit=51", tokenA, nil, nil), 400)
	requireStatus(t, f.request(t, "GET", "/v1/history?token=secret", tokenA, nil, nil), 400)
}
func TestHistoryPaginationFiltersAndVideoOnlyRecords(t *testing.T) {
	f := setup(t)
	updates := []storage.SourceUpdate{}
	for n := 1; n <= 55; n++ {
		id := fmt.Sprintf("00000000-0000-4000-8000-%012x", n)
		updates = append(updates, sourceUpdate(id, int64(n)))
	}
	requireStatus(t, f.request(t, "POST", "/v1/source", tokenA, updates, map[string]string{"X-Mutation-ID": mid(1)}), 200)
	f.video(t)
	seen := map[string]bool{}
	cursor := ""
	videoSeen := false
	for attempts := 0; attempts < 10; attempts++ {
		var page struct {
			Records []storage.Record
			Videos  []storage.Video
			Next    string
		}
		r := f.request(t, "GET", "/v1/history?limit=7&after="+url.QueryEscape(cursor), tokenB, nil, nil)
		requireStatus(t, r, 200)
		if e := json.NewDecoder(r.Body).Decode(&page); e != nil {
			t.Fatal(e)
		}
		if len(page.Records) > 7 || len(page.Videos) > 7 {
			t.Fatal("page unbounded")
		}
		for _, rec := range page.Records {
			if seen[rec.ID] {
				t.Fatal("duplicate record page")
			}
			seen[rec.ID] = true
		}
		videoSeen = videoSeen || len(page.Videos) > 0
		if page.Next == "" {
			break
		}
		if page.Next <= cursor {
			t.Fatal("cursor did not progress")
		}
		cursor = page.Next
	}
	if len(seen) != 55 || !videoSeen {
		t.Fatal("incomplete pagination", len(seen), videoSeen)
	}
	var filtered struct{ Records []storage.Record }
	r := f.request(t, "GET", "/v1/history?since=50&until=55&type=work&status=PLANNED&device="+deviceA, tokenB, nil, nil)
	requireStatus(t, r, 200)
	json.NewDecoder(r.Body).Decode(&filtered)
	if len(filtered.Records) != 6 {
		t.Fatal("filters wrong")
	}
}
func TestHeadersAndWebUseTextNotHTML(t *testing.T) {
	f := setup(t)
	f.add(t)
	r := f.request(t, "GET", "/", tokenA, nil, nil)
	requireStatus(t, r, 200)
	if r.Header.Get("Cache-Control") != "no-store" || r.Header.Get("X-Content-Type-Options") != "nosniff" || !strings.Contains(r.Header.Get("Content-Security-Policy"), "frame-ancestors 'none'") {
		t.Fatal("security headers")
	}
	if bytes.Contains(script, []byte("innerHTML")) || !bytes.Contains(script, []byte("textContent")) {
		t.Fatal("unsafe metadata rendering")
	}
}
func TestMissingCredentialsAndChangedArchiveFailClosed(t *testing.T) {
	f := setup(t)
	f.api.Credentials = func() (auth.Credentials, error) { return auth.Credentials{}, os.ErrNotExist }
	requireStatus(t, f.request(t, "GET", "/v1/history", tokenA, nil, nil), 503)
}
func TestProductionConfigRejectsUnsafeListeners(t *testing.T) {
	valid := Config{Address: "100.64.1.2", Hostname: "host.tailnet.ts.net", Port: 8443, Certificate: "cert", Key: "key", Credentials: "auth", State: "state"}
	if e := valid.Validate(); e != nil {
		t.Fatal(e)
	}
	for _, address := range []string{"0.0.0.0", "::", "127.0.0.1", "192.168.1.2", "8.8.8.8"} {
		bad := valid
		bad.Address = address
		if bad.Validate() == nil {
			t.Fatal("unsafe listener", address)
		}
	}
	for _, hostname := range []string{"host.local", "host.ts.net/evil", "HOST.ts.net", "host.ts.net:8443"} {
		bad := valid
		bad.Hostname = hostname
		if bad.Validate() == nil {
			t.Fatal("unsafe hostname")
		}
	}
	for _, port := range []int{0, 80, 443, 65536} {
		bad := valid
		bad.Port = port
		if bad.Validate() == nil {
			t.Fatal("privileged/invalid port")
		}
	}
}
