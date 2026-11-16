package main

import (
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
)

func TestUnwrapTLSListener(t *testing.T) {
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer inner.Close()
	got, ok := unwrapTLSListener(tls.NewListener(inner, &tls.Config{}))
	if !ok || got != inner {
		t.Fatal("listener interno non trovato")
	}
}

// La 8443 deve essere un passthrough TCP: il client vede il certificato della nostra CA servito dal backend.
func TestTelemetryPassthrough(t *testing.T) {
	dir := t.TempDir()
	c := Certs{dir: dir}
	const host = "vigilo.example.ts.net"
	if err := c.Ensure(host); err != nil {
		t.Fatal(err)
	}
	cert, err := tls.LoadX509KeyPair(c.certPath(), c.keyPath())
	if err != nil {
		t.Fatal(err)
	}
	backend, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	go func() {
		for {
			bc, err := backend.Accept()
			if err != nil {
				return
			}
			go func() { defer bc.Close(); _, _ = io.Copy(bc, bc) }()
		}
	}()
	front, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer front.Close()
	go func() {
		for {
			fc, err := front.Accept()
			if err != nil {
				return
			}
			go proxyTelemetryConn(fc, backend.Addr().String())
		}
	}()
	caPEM, _ := os.ReadFile(filepath.Join(dir, "ca.crt"))
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM(caPEM)
	conn, err := tls.Dial("tcp", front.Addr().String(), &tls.Config{RootCAs: pool, ServerName: host})
	if err != nil {
		t.Fatalf("handshake attraverso il passthrough: %v", err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("eco: %q %v", buf, err)
	}
}
