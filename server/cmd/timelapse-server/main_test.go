package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"timelapse/internal/auth"
	"timelapse/internal/httpapi"
)

const deviceID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

func TestNativeAdminEnrollmentRevocationAndNoSecretOnCommandLine(t *testing.T) {
	root := filepath.Join(t.TempDir(), "private")
	if e := os.Mkdir(root, 0700); e != nil {
		t.Fatal(e)
	}
	password := filepath.Join(root, "password")
	credentials := filepath.Join(root, "credentials.json")
	token := filepath.Join(root, "pixel-token")
	if e := os.WriteFile(password, []byte("long private fixture password\n"), 0600); e != nil {
		t.Fatal(e)
	}
	if e := run([]string{"init-auth", "--credentials", credentials, "--username", "owner", "--password-file", password}); e != nil {
		t.Fatal(e)
	}
	alias := root + "-alias"
	if e := os.Symlink(root, alias); e != nil {
		t.Fatal(e)
	}
	for _, unsafe := range []string{"relative-token", filepath.Join(alias, "token"), root + "/../private/noncanonical-token"} {
		if e := run([]string{"enroll", "--credentials", credentials, "--device", deviceID, "--name", "Pixel", "--token-file", unsafe}); e == nil {
			t.Fatal("unsafe token path accepted")
		}
	}
	if _, e := os.Stat(filepath.Join(root, "token")); !os.IsNotExist(e) {
		t.Fatal("aliased secret output was created")
	}
	if e := run([]string{"enroll", "--credentials", credentials, "--device", deviceID, "--name", "Pixel", "--token-file", token}); e != nil {
		t.Fatal(e)
	}
	secret, e := auth.ReadSecret(token)
	if e != nil {
		t.Fatal(e)
	}
	c, e := auth.Load(credentials)
	if e != nil {
		t.Fatal(e)
	}
	if id, ok := c.Device(secret); !ok || id != deviceID {
		t.Fatal("enrollment not usable")
	}
	raw, _ := os.ReadFile(credentials)
	if strings.Contains(string(raw), secret) || strings.Contains(string(raw), "fixture password") {
		t.Fatal("plaintext secret in state")
	}
	if e = run([]string{"enroll", "--credentials", credentials, "--device", deviceID, "--name", "Pixel", "--token-file", token}); e == nil {
		t.Fatal("token overwritten")
	}
	if e = run([]string{"revoke", "--credentials", credentials, "--device", deviceID}); e != nil {
		t.Fatal(e)
	}
	c, e = auth.Load(credentials)
	if e != nil {
		t.Fatal(e)
	}
	if _, ok := c.Device(secret); ok {
		t.Fatal("revocation failed")
	}
	if e = run([]string{"init-auth", "--credentials", credentials, "--username", "owner", "--password", "inline-secret"}); e == nil {
		t.Fatal("inline password accepted")
	}
}
func certificateFixture(t *testing.T, host string, notAfter time.Time) httpapi.Config {
	t.Helper()
	root := t.TempDir()
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: host}, DNSNames: []string{host}, NotBefore: time.Now().Add(-time.Hour), NotAfter: notAfter, KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, e := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	keyBytes, e := x509.MarshalPKCS8PrivateKey(key)
	if e != nil {
		t.Fatal(e)
	}
	c := httpapi.Config{Hostname: host, Certificate: filepath.Join(root, "cert.pem"), Key: filepath.Join(root, "key.pem")}
	os.WriteFile(c.Certificate, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600)
	os.WriteFile(c.Key, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyBytes}), 0600)
	return c
}
func TestCertificateHostnameExpiryAndPrivateKeyFailClosed(t *testing.T) {
	c := certificateFixture(t, "fixture.tailnet.ts.net", time.Now().Add(time.Hour))
	if _, e := loadCertificate(c); e != nil {
		t.Fatal(e)
	}
	bad := c
	bad.Hostname = "different.tailnet.ts.net"
	if _, e := loadCertificate(bad); e == nil {
		t.Fatal("certificate hostname mismatch accepted")
	}
	os.Chmod(c.Key, 0644)
	if _, e := loadCertificate(c); e == nil {
		t.Fatal("public private key accepted")
	}
	expired := certificateFixture(t, "fixture.tailnet.ts.net", time.Now().Add(-time.Minute))
	if _, e := loadCertificate(expired); e == nil {
		t.Fatal("expired certificate accepted")
	}
}
func TestUnknownCommandAndUnsafeConfigHaveNoListener(t *testing.T) {
	if e := run(nil); e == nil {
		t.Fatal("missing command")
	}
	if e := run([]string{"not-a-command"}); e == nil {
		t.Fatal("unknown command")
	}
	path := filepath.Join(t.TempDir(), "config.json")
	os.WriteFile(path, []byte(`{"address":"0.0.0.0","hostname":"host.ts.net","port":8443,"certificate":"absent","key":"absent","credentials":"absent","state":"absent"}`), 0600)
	if e := run([]string{"serve", "--config", path}); e == nil {
		t.Fatal("unsafe service configured")
	}
}
