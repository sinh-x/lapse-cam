// timelapse-server never configures Tailscale, certificates, firewall or other services.
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"timelapse/internal/auth"
	"timelapse/internal/httpapi"
	"timelapse/internal/merge"
	"timelapse/internal/storage"
)

func main() {
	if e := run(os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, "timelapse-server:", e)
		os.Exit(1)
	}
}
func loadCertificate(c httpapi.Config) (tls.Certificate, error) {
	if e := auth.CheckPrivateFile(c.Key); e != nil {
		return tls.Certificate{}, e
	}
	pair, e := tls.LoadX509KeyPair(c.Certificate, c.Key)
	if e != nil {
		return pair, e
	}
	leaf, e := x509.ParseCertificate(pair.Certificate[0])
	if e != nil {
		return pair, e
	}
	if e = leaf.VerifyHostname(c.Hostname); e != nil {
		return pair, e
	}
	now := time.Now()
	if now.Before(leaf.NotBefore) || !now.Before(leaf.NotAfter) {
		return pair, fmt.Errorf("certificate is not currently valid")
	}
	return pair, nil
}
func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("use serve, init-auth, enroll or revoke; see docs/PRIVATE_SYNC_SERVER.md")
	}
	f := flag.NewFlagSet(args[0], flag.ContinueOnError)
	config := f.String("config", "", "private JSON configuration path")
	credentials := f.String("credentials", "", "private credentials JSON path")
	password := f.String("password-file", "", "mode0600 password input file (never command-line password)")
	tokenFile := f.String("token-file", "", "new mode0600 credential output file")
	user := f.String("username", "", "private browser account name")
	device := f.String("device", "", "canonical device UUID")
	name := f.String("name", "", "device display name")
	if e := f.Parse(args[1:]); e != nil {
		return e
	}
	if f.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments")
	}
	switch args[0] {
	case "init-auth":
		secret, e := auth.ReadSecret(*password)
		if e != nil {
			return e
		}
		c, e := auth.Init(*user, secret)
		if e != nil {
			return e
		}
		return auth.Save(*credentials, func(target *auth.Credentials) error { *target = c; return nil }, true)
	case "enroll":
		if !merge.ID(*device) || strings.TrimSpace(*name) == "" || len(*name) > 120 || !filepath.IsAbs(*tokenFile) || filepath.Clean(*tokenFile) != *tokenFile {
			return fmt.Errorf("device UUID, name and new token-file required")
		}
		current, err := auth.Load(*credentials)
		if err != nil {
			return err
		}
		if _, exists := current.Devices[*device]; exists {
			return fmt.Errorf("device exists; no credential replacement")
		}
		// Write and directory-sync the token before credential publication.
		parentPath := filepath.Dir(*tokenFile)
		real, err := filepath.EvalSymlinks(parentPath)
		if err != nil || real != parentPath {
			return fmt.Errorf("canonical private token parent required")
		}
		parent, err := os.Stat(parentPath)
		if err != nil || parent.Mode().Perm() != 0700 {
			return fmt.Errorf("token-file parent must exist with mode0700")
		}
		token := auth.Random()
		out, e := os.OpenFile(*tokenFile, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e != nil {
			return e
		}
		if _, e = io.WriteString(out, token+"\n"); e != nil {
			out.Close()
			return e
		}
		if e = out.Sync(); e != nil {
			out.Close()
			return e
		}
		if e = out.Close(); e != nil {
			return e
		}
		dir, e := os.Open(parentPath)
		if e != nil {
			return e
		}
		e = dir.Sync()
		dir.Close()
		if e != nil {
			return e
		}
		return auth.Save(*credentials, func(c *auth.Credentials) error {
			if _, exists := c.Devices[*device]; exists {
				return fmt.Errorf("device exists; no credential replacement")
			}
			c.Devices[*device] = auth.Device{Name: *name, Hash: httpapi.TokenDigest(token)}
			return nil
		}, false)
	case "revoke":
		if !merge.ID(*device) {
			return fmt.Errorf("canonical device UUID required")
		}
		return auth.Save(*credentials, func(c *auth.Credentials) error {
			d, ok := c.Devices[*device]
			if !ok {
				return fmt.Errorf("unknown device")
			}
			d.Revoked = true
			c.Devices[*device] = d
			return nil
		}, false)
	case "serve":
		raw, e := os.ReadFile(*config)
		if e != nil {
			return e
		}
		var c httpapi.Config
		if e = storage.Strict(raw, &c); e != nil {
			return e
		}
		if e = c.Validate(); e != nil {
			return e
		}
		if os.Geteuid() == 0 {
			return fmt.Errorf("refusing root service")
		}
		if e = httpapi.CheckTailnetInterface(c.Address); e != nil {
			return e
		}
		if _, e = auth.Load(c.Credentials); e != nil {
			return e
		}
		if _, e = loadCertificate(c); e != nil {
			return e
		}
		store, e := storage.Open(c.State)
		if e != nil {
			return e
		}
		defer store.Close()
		api := httpapi.New(store, func() (auth.Credentials, error) { return auth.Load(c.Credentials) }, c.Authority())
		server := &http.Server{Addr: net.JoinHostPort(c.Address, strconv.Itoa(c.Port)), Handler: httpapi.TailnetOnly(api), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 2 * time.Minute, WriteTimeout: 5 * time.Minute, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12, GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
			// Reload on each handshake so an operator-managed atomic renewal needs no service takeover.
			fresh, err := loadCertificate(c)
			if err != nil {
				return nil, err
			}
			return &fresh, nil
		}}, ErrorLog: log.New(io.Discard, "", 0)}
		ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
		defer stop()
		go func() {
			<-ctx.Done()
			shutdown, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			_ = server.Shutdown(shutdown)
		}()
		e = server.ListenAndServeTLS("", "")
		if e == http.ErrServerClosed {
			return nil
		}
		return e
	default:
		return fmt.Errorf("unknown command")
	}
}
