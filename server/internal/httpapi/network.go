package httpapi

import (
	"errors"
	"net"
	"net/http"
	"net/netip"
)

var tailnetV4 = netip.MustParsePrefix("100.64.0.0/10")
var tailnetV6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")

func tailnetIP(ip netip.Addr) bool { return tailnetV4.Contains(ip) || tailnetV6.Contains(ip) }

// Production never treats forwarded headers or an independent LAN peer as tailnet identity.
// This is an extra network bound, not a replacement for TLS and application authorization.
func TailnetOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		ip, e := netip.ParseAddr(host)
		if err != nil || e != nil || !tailnetIP(ip) {
			http.Error(w, "tailnet peer required", 403)
			return
		}
		next.ServeHTTP(w, r)
	})
}
func CheckTailnetInterface(address string) error {
	iface, err := net.InterfaceByName("tailscale0")
	if err != nil {
		return errors.New("tailscale0 interface required; no LAN or userspace fallback")
	}
	if iface.Flags&net.FlagUp == 0 {
		return errors.New("tailscale0 interface is down")
	}
	addrs, err := iface.Addrs()
	if err != nil {
		return err
	}
	return checkInterfaceAddresses(address, addrs)
}
func checkInterfaceAddresses(address string, addrs []net.Addr) error {
	ip, err := netip.ParseAddr(address)
	if err != nil || !tailnetIP(ip) {
		return errors.New("explicit tailnet address required")
	}
	for _, addr := range addrs {
		prefix, e := netip.ParsePrefix(addr.String())
		if e == nil && prefix.Addr() == ip {
			return nil
		}
	}
	return errors.New("configured address is not assigned to tailscale0")
}
