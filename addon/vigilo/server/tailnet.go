package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// Tailnet: nodo Tailscale dedicato a Vigilo (es. vigilo.tailXXXX.ts.net) creato dentro il componente
// aggiuntivo con tsnet. Apre da solo il Funnel:
//
//	443  -> HTTPS terminato qui: chiave pubblica, ritorno del login, API per l'app
//	8443 -> TCP grezzo inoltrato da Vigilo a fleet-telemetry (mTLS dell'auto intatto)
//
// Non serve toccare l'add-on Tailscale di Home Assistant né usare SSH.
type Tailnet struct {
	srv       *tsnet.Server
	telemetry string // es. 127.0.0.1:4443

	telemetryOpen bool // listener Funnel 8443 già aperto (solo goroutine Run)

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
		Logf:      tsnetLogf, // log di Tailscale filtrati (solo Funnel/serve)
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
	// 8443: TCP grezzo verso fleet-telemetry, gestito da Vigilo (niente terminazione TLS: il certificato
	// è quello della nostra CA e l'mTLS dell'auto arriva intatto a fleet-telemetry).
	// Prima si usava il "TCPForward" della serve config di Tailscale, ma dall'esterno la porta chiudeva
	// subito la connessione senza mai rispondere con TLS: ora la connessione la accettiamo e la inoltriamo noi.
	if err := t.clearServeTCP(ctx, lc, 8443); err != nil {
		log.Printf("serve config 8443: %v", err)
	}
	// La 443 si apre subito (chiave pubblica, login). La 8443 solo quando fleet-telemetry è in ascolto,
	// così l'auto non riceve "connection refused" nel primo minuto di avvio.

	// 443: HTTPS (certificato Let's Encrypt gestito da Tailscale). Si registra l'SNI di ogni client
	// per poter spiegare eventuali handshake falliti nel registro.
	tlsCfg := &tls.Config{
		GetCertificate: lc.GetCertificate,
		GetConfigForClient: func(hi *tls.ClientHelloInfo) (*tls.Config, error) {
			if hi.Conn != nil {
				sniSeen.Store(hi.Conn.RemoteAddr().String(), hi.ServerName)
			}
			return nil, nil
		},
	}
	ln, err := t.srv.ListenFunnel("tcp", ":443", tsnet.FunnelTLSConfig(tlsCfg))
	if err != nil {
		return fmt.Errorf("Funnel 443: %w", err)
	}
	t.mu.Lock()
	t.served = true
	t.funnel = nil
	t.mu.Unlock()
	log.Printf("Funnel attivo su https://%s (443); porta 8443 in attesa di fleet-telemetry", domain)
	go t.openTelemetryFunnel(ctx, domain)
	// Il certificato Let's Encrypt viene ottenuto alla prima connessione e può richiedere decine di
	// secondi: lo chiediamo subito, così i primi client (es. Tesla che scarica la chiave) non vanno in timeout.
	go func() {
		cctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
		defer cancel()
		start := time.Now()
		if _, _, err := lc.CertPair(cctx, domain); err != nil {
			log.Printf("certificato HTTPS per %s: %v", domain, err)
			return
		}
		log.Printf("certificato HTTPS pronto per %s (%s)", domain, time.Since(start).Round(time.Second))
	}()
	go func() {
		s := &http.Server{
			Handler: handler,
			// Il timeout più breve vale anche per l'handshake TLS: 60 s lascia tempo al certificato.
			ReadHeaderTimeout: 60 * time.Second,
			IdleTimeout:       2 * time.Minute,
			ErrorLog:          log.New(tlsNoiseFilter{}, "", 0),
		}
		if err := s.Serve(ln); err != nil && !errors.Is(err, net.ErrClosed) {
			t.setErr(fmt.Errorf("server pubblico: %w", err))
			t.mu.Lock()
			t.served = false
			t.mu.Unlock()
		}
	}()
	return nil
}

// sniSeen: indirizzo remoto -> SNI richiesto, per arricchire i messaggi di handshake fallito sulla 443.
var sniSeen sync.Map

var handshakeRe = regexp.MustCompile(`TLS handshake error from (\S+): (.*)$`)

// tlsNoiseFilter riscrive gli errori di handshake TLS della porta 443 con porta, client, SNI e motivo;
// gli altri messaggi del server HTTP passano invariati.
type tlsNoiseFilter struct{}

func (tlsNoiseFilter) Write(p []byte) (int, error) {
	m := strings.TrimSpace(string(p))
	if sm := handshakeRe.FindStringSubmatch(m); sm != nil {
		sni := "nessuno"
		if v, ok := sniSeen.LoadAndDelete(sm[1]); ok && v.(string) != "" {
			sni = v.(string)
		}
		log.Printf("HTTPS pubblico (porta 443): handshake TLS fallito da %s, SNI %s: %s", sm[1], sni, truncate(sm[2], 160))
		return len(p), nil
	}
	log.Print(m)
	return len(p), nil
}

// tsnetLogf: i log di Tailscale sono molto verbosi; si tengono solo quelli su Funnel/serve/ingress,
// utili per capire perché una connessione dall'esterno non arriva.
func tsnetLogf(f string, a ...any) {
	m := fmt.Sprintf(f, a...)
	for _, k := range []string{"handleIngress", "TCP proxy", "serve", "funnel", "Funnel"} {
		if strings.Contains(m, k) {
			log.Printf("tailscale: %s", truncate(m, 300))
			return
		}
	}
}

// clearServeTCP toglie un vecchio inoltro TCP della serve config (versioni ≤ 1.0.2) per la porta data.
func (t *Tailnet) clearServeTCP(ctx context.Context, lc *local.Client, port uint16) error {
	sc, err := lc.GetServeConfig(ctx)
	if err != nil || sc == nil || sc.TCP == nil {
		return err
	}
	if _, ok := sc.TCP[port]; !ok {
		return nil
	}
	delete(sc.TCP, port)
	return lc.SetServeConfig(ctx, sc)
}

// unwrapTLSListener: tsnet.ListenFunnel restituisce sempre un listener TLS (tls.NewListener); per la
// telemetria serve la connessione TCP grezza, che è il campo esportato "Listener" del wrapper.
func unwrapTLSListener(ln net.Listener) (net.Listener, bool) {
	v := reflect.ValueOf(ln)
	if v.Kind() == reflect.Pointer {
		v = v.Elem()
	}
	if v.Kind() != reflect.Struct {
		return nil, false
	}
	f := v.FieldByName("Listener")
	if !f.IsValid() || !f.CanInterface() {
		return nil, false
	}
	inner, ok := f.Interface().(net.Listener)
	return inner, ok && inner != nil
}

// openTelemetryFunnel attende che fleet-telemetry sia in ascolto, poi apre il Funnel 8443.
// Se dopo due minuti non è ancora pronto, apre comunque la porta: l'inoltro ritenta il collegamento.
func (t *Tailnet) openTelemetryFunnel(ctx context.Context, domain string) {
	t.mu.Lock()
	already := t.telemetryOpen
	t.mu.Unlock()
	if already {
		return
	}
	if err := waitTCP(ctx, t.telemetry, 2*time.Minute); err != nil {
		log.Printf("telemetria: fleet-telemetry non ancora in ascolto su %s (%v); apro comunque la porta 8443", t.telemetry, err)
	} else {
		log.Printf("telemetria: fleet-telemetry in ascolto su %s, apro la porta pubblica 8443", t.telemetry)
	}
	for ctx.Err() == nil {
		t.mu.Lock()
		already = t.telemetryOpen
		t.mu.Unlock()
		if already {
			return
		}
		ln8443, err := t.srv.ListenFunnel("tcp", ":8443", tsnet.FunnelOnly())
		if err != nil {
			log.Printf("Funnel 8443: %v; nuovo tentativo tra 10s", err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(10 * time.Second):
			}
			continue
		}
		raw, ok := unwrapTLSListener(ln8443)
		if !ok {
			ln8443.Close()
			log.Printf("Funnel 8443: impossibile ottenere il listener TCP grezzo; nuovo tentativo tra 10s")
			select {
			case <-ctx.Done():
				return
			case <-time.After(10 * time.Second):
			}
			continue
		}
		t.mu.Lock()
		t.telemetryOpen = true
		t.mu.Unlock()
		log.Printf("Funnel attivo su https://%s (443) e %s:8443", domain, domain)
		t.serveTelemetry(ctx, raw)
		return
	}
}

// waitTCP attende che addr accetti connessioni TCP, o scade.
func waitTCP(ctx context.Context, addr string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	var last error
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		c, err := net.DialTimeout("tcp", addr, time.Second)
		if err == nil {
			c.Close()
			return nil
		}
		last = err
		if !time.Now().Before(deadline) {
			if last == nil {
				last = errors.New("timeout")
			}
			return last
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(400 * time.Millisecond):
		}
	}
}

// serveTelemetry inoltra ogni connessione Funnel della 8443 a fleet-telemetry senza toccare il TLS.
func (t *Tailnet) serveTelemetry(ctx context.Context, ln net.Listener) {
	go func() { <-ctx.Done(); ln.Close() }()
	log.Printf("telemetria: porta pubblica 8443 inoltrata (TCP grezzo) a fleet-telemetry su %s", t.telemetry)
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() == nil && !errors.Is(err, net.ErrClosed) {
				log.Printf("telemetria: accept 8443: %v", err)
			}
			return
		}
		go proxyTelemetryConn(c, t.telemetry)
	}
}

func proxyTelemetryConn(c net.Conn, backend string) {
	defer c.Close()
	start := time.Now()
	src := c.RemoteAddr().String()
	// Legge il ClientHello (senza rispondere) solo per registrare l'SNI, poi lo ripassa a fleet-telemetry.
	_ = c.SetReadDeadline(time.Now().Add(15 * time.Second))
	sni, hello, herr := peekSNI(c)
	_ = c.SetReadDeadline(time.Time{})
	if herr != nil && len(hello) == 0 {
		log.Printf("telemetria (porta 8443): connessione da %s chiusa prima del ClientHello: %v", src, herr)
		return
	}
	if sni == "" {
		sni = "nessuno"
	}
	b, err := dialTelemetry(backend, 20*time.Second)
	if err != nil {
		log.Printf("telemetria (porta 8443): da %s (SNI %s) ma fleet-telemetry non risponde su %s: %v", src, sni, backend, err)
		return
	}
	defer b.Close()
	log.Printf("telemetria (porta 8443): connessione da %s, SNI %s → fleet-telemetry", src, sni)
	if _, err := b.Write(hello); err != nil {
		log.Printf("telemetria (porta 8443): inoltro a fleet-telemetry: %v", err)
		return
	}
	var up, down int64
	done := make(chan struct{})
	go func() {
		down, _ = io.Copy(c, b)
		if tc, ok := c.(interface{ CloseWrite() error }); ok {
			_ = tc.CloseWrite()
		}
		close(done)
	}()
	up, _ = io.Copy(b, c)
	if tc, ok := b.(*net.TCPConn); ok {
		_ = tc.CloseWrite()
	}
	<-done
	log.Printf("telemetria (porta 8443): connessione da %s chiusa dopo %s (%d B ricevuti, %d B inviati)",
		src, time.Since(start).Round(time.Second), up+int64(len(hello)), down)
}

// dialTelemetry ritenta il collegamento a fleet-telemetry per qualche secondo
// (es. se il processo è appena stato avviato o sta ripartendo).
func dialTelemetry(backend string, timeout time.Duration) (net.Conn, error) {
	deadline := time.Now().Add(timeout)
	var last error
	for {
		remain := time.Until(deadline)
		if remain <= 0 {
			if last == nil {
				last = errors.New("timeout")
			}
			return nil, last
		}
		d := 2 * time.Second
		if d > remain {
			d = remain
		}
		c, err := net.DialTimeout("tcp", backend, d)
		if err == nil {
			return c, nil
		}
		last = err
		sleep := 400 * time.Millisecond
		if time.Now().Add(sleep).After(deadline) {
			return nil, last
		}
		time.Sleep(sleep)
	}
}

// peekSNI legge il ClientHello TLS e ne estrae l'SNI senza inviare nulla al client.
// Restituisce i byte letti, da ripassare al server vero.
func peekSNI(c net.Conn) (string, []byte, error) {
	rec := &recordingConn{Conn: c}
	var sni string
	errStop := errors.New("stop")
	_ = tls.Server(rec, &tls.Config{GetConfigForClient: func(hi *tls.ClientHelloInfo) (*tls.Config, error) {
		sni = hi.ServerName
		return nil, errStop
	}}).Handshake()
	if sni == "" && len(rec.buf) == 0 {
		return "", nil, errors.New("nessun dato")
	}
	return sni, rec.buf, nil
}

// recordingConn registra ciò che si legge e scarta le scritture (nessun alert TLS verso il client).
type recordingConn struct {
	net.Conn
	buf []byte
}

func (r *recordingConn) Read(p []byte) (int, error) {
	n, err := r.Conn.Read(p)
	r.buf = append(r.buf, p[:n]...)
	return n, err
}
func (r *recordingConn) Write(p []byte) (int, error) { return len(p), nil }

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
