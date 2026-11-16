package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// Tailnet: nodo Tailscale dedicato a Vigilo (es. vigilo.tailXXXX.ts.net) creato dentro il componente
// aggiuntivo con tsnet. Apre da solo il Funnel:
//
//	443  -> HTTPS terminato qui: chiave pubblica, ritorno del login, API per l'app
//	8443 -> TCP passthrough verso fleet-telemetry (mTLS dell'auto intatto)
//
// Non serve toccare l'add-on Tailscale di Home Assistant né usare SSH.
type Tailnet struct {
	srv       *tsnet.Server
	telemetry string // es. 127.0.0.1:4443

	mu      sync.Mutex
	host    string
	state   string
	authURL string
	funnel  error
	served  bool
}

type TailnetStatus struct {
	State   string `json:"state"` // NeedsLogin, Starting, Running, ...
	AuthURL string `json:"auth_url"`
	Host    string `json:"host"`
	Funnel  bool   `json:"funnel"`
	Error   string `json:"error"`
}

func NewTailnet(dataDir, hostname, authKey, telemetryAddr string) *Tailnet {
	srv := &tsnet.Server{
		Dir:       filepath.Join(dataDir, "tailscale"),
		Hostname:  hostname,
		AuthKey:   authKey,
		Ephemeral: false,
		Logf:      func(string, ...any) {}, // log di Tailscale molto verbosi: silenziati
		UserLogf:  func(f string, a ...any) { log.Printf("tailscale: "+f, a...) },
	}
	return &Tailnet{srv: srv, telemetry: telemetryAddr, state: "Starting"}
}

// Run avvia il nodo, attende il login e apre il Funnel; poi serve handler sulla porta 443.
func (t *Tailnet) Run(ctx context.Context, handler http.Handler) {
	if err := t.srv.Start(); err != nil {
		t.setErr(fmt.Errorf("avvio Tailscale: %w", err))
		return
	}
	lc, err := t.srv.LocalClient()
	if err != nil {
		t.setErr(err)
		return
	}
	for ctx.Err() == nil {
		st, err := lc.StatusWithoutPeers(ctx)
		if err == nil {
			t.mu.Lock()
			t.state = st.BackendState
			t.authURL = st.AuthURL
			if st.Self != nil {
				t.host = strings.TrimSuffix(st.Self.DNSName, ".")
			}
			running := st.BackendState == "Running" && t.host != ""
			served := t.served
			t.mu.Unlock()
			if running && !served {
				if err := t.openFunnel(ctx, handler); err != nil {
					t.setErr(err)
				}
			}
		}
		select {
		case <-ctx.Done():
		case <-time.After(5 * time.Second):
		}
	}
}

func (t *Tailnet) openFunnel(ctx context.Context, handler http.Handler) error {
	lc, err := t.srv.LocalClient()
	if err != nil {
		return err
	}
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return err
	}
	if len(st.CertDomains) == 0 {
		return errors.New("HTTPS non attivo nel tailnet: abilitalo in Tailscale (DNS → HTTPS Certificates)")
	}
	domain := st.CertDomains[0]
	if err := ipn.CheckFunnelAccess(8443, st.Self); err != nil {
		return fmt.Errorf("Funnel non consentito per questo nodo: %w", err)
	}
	// 8443: TCP passthrough verso fleet-telemetry.
	sc, err := lc.GetServeConfig(ctx)
	if err != nil {
		return err
	}
	if sc == nil {
		sc = &ipn.ServeConfig{}
	}
	if sc.TCP == nil {
		sc.TCP = map[uint16]*ipn.TCPPortHandler{}
	}
	sc.TCP[8443] = &ipn.TCPPortHandler{TCPForward: t.telemetry}
	if sc.AllowFunnel == nil {
		sc.AllowFunnel = map[ipn.HostPort]bool{}
	}
	sc.AllowFunnel[ipn.HostPort(domain+":8443")] = true
	if err := lc.SetServeConfig(ctx, sc); err != nil {
		return fmt.Errorf("configurazione Funnel 8443: %w", err)
	}
	// 443: HTTPS (certificato Let's Encrypt gestito da Tailscale).
	ln, err := t.srv.ListenFunnel("tcp", ":443")
	if err != nil {
		return fmt.Errorf("Funnel 443: %w", err)
	}
	t.mu.Lock()
	t.served = true
	t.funnel = nil
	t.mu.Unlock()
	log.Printf("Funnel attivo su https://%s (443) e %s:8443", domain, domain)
	go func() {
		s := &http.Server{Handler: handler, ReadHeaderTimeout: 15 * time.Second}
		if err := s.Serve(ln); err != nil && !errors.Is(err, net.ErrClosed) {
			t.setErr(fmt.Errorf("server pubblico: %w", err))
			t.mu.Lock()
			t.served = false
			t.mu.Unlock()
		}
	}()
	return nil
}

func (t *Tailnet) setErr(err error) {
	log.Printf("tailscale: %v", err)
	t.mu.Lock()
	t.funnel = err
	t.mu.Unlock()
}

func (t *Tailnet) Status() TailnetStatus {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := TailnetStatus{State: t.state, AuthURL: t.authURL, Host: t.host, Funnel: t.served}
	if t.funnel != nil {
		s.Error = t.funnel.Error()
	}
	return s
}

// Host restituisce il nome pubblico (vuoto finché il nodo non è collegato).
func (t *Tailnet) Host() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.host
}

// Logout scollega il nodo (per cambiare account Tailscale).
func (t *Tailnet) Logout(ctx context.Context) error {
	lc, err := t.srv.LocalClient()
	if err != nil {
		return err
	}
	t.mu.Lock()
	t.served = false
	t.host = ""
	t.mu.Unlock()
	return lc.Logout(ctx)
}

// Login forza la generazione di un nuovo link di accesso.
func (t *Tailnet) StartLogin(ctx context.Context) error {
	lc, err := t.srv.LocalClient()
	if err != nil {
		return err
	}
	return lc.StartLoginInteractive(ctx)
}
