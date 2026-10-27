package httpapi

import (
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNativePeerBoundaryDoesNotTrustForwardedHeaders(t *testing.T) {
	for _, peer := range []struct {
		address string
		status  int
	}{{"100.64.1.2:1234", 200}, {"[fd7a:115c:a1e0::1]:1234", 200}, {"127.0.0.1:1234", 403}, {"192.168.1.2:1234", 403}, {"8.8.8.8:1234", 403}, {"invalid", 403}} {
		handler := TailnetOnly(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
		request := httptest.NewRequest("GET", "https://fixture.ts.net:8443/", nil)
		request.RemoteAddr = peer.address
		request.Header.Set("X-Forwarded-For", "100.64.1.2")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != peer.status {
			t.Fatal("wrong native peer boundary", peer.address, response.Code)
		}
	}
}
func TestConfiguredAddressMustBePresentOnInspectedTailnetInterface(t *testing.T) {
	_, v4, _ := net.ParseCIDR("100.64.1.2/32")
	_, v6, _ := net.ParseCIDR("fd7a:115c:a1e0::1/128")
	addrs := []net.Addr{v4, v6}
	for _, ip := range []string{"100.64.1.2", "fd7a:115c:a1e0::1"} {
		if err := checkInterfaceAddresses(ip, addrs); err != nil {
			t.Fatal(err)
		}
	}
	for _, ip := range []string{"0.0.0.0", "192.168.1.2", "100.64.1.3", "fd7a:115c:a1e0::2"} {
		if err := checkInterfaceAddresses(ip, addrs); err == nil {
			t.Fatal("nonassigned/non-tailnet address accepted")
		}
	}
}
