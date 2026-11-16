package main

// Modalità "standalone": Vigilo in un container Docker qualsiasi, senza Home Assistant né Supervisor.
// Tutto ciò che qui dentro è nuovo si attiva solo con -mode standalone (o VIGILO_MODE=standalone):
// in modalità componente aggiuntivo (predefinita) nessuna di queste funzioni viene chiamata.
//
// Differenze rispetto al componente aggiuntivo:
//   - configurazione da variabili d'ambiente (oltre a un eventuale /data/options.json);
//   - broker MQTT interno (mosquitto, solo su 127.0.0.1) tra fleet-telemetry e Vigilo, se non ne indichi uno;
//   - interfaccia web su http://server:8099 protetta da password (in HA la protegge l'Ingress);
//   - notifiche: l'app Vigilo legge gli eventi dall'API (come già fa), più ntfy facoltativo.

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	modeHA         = "ha"
	modeStandalone = "standalone"
	// Porta del broker interno: insolita, così non si scontra con un mosquitto già presente (rete "host").
	localBrokerPort = 11883
	uiUser          = "vigilo"
)

var hostnameRe = regexp.MustCompile(`^[a-z0-9-]{1,40}$`)

// firstEnv restituisce il primo valore non vuoto tra le variabili indicate.
func firstEnv(names ...string) string {
	for _, n := range names {
		if v := strings.TrimSpace(os.Getenv(n)); v != "" {
			return v
		}
	}
	return ""
}

// applyStandaloneEnv sovrascrive le opzioni con le variabili d'ambiente (solo modalità standalone).
// Non registra mai i valori segreti.
func applyStandaloneEnv(o *Options) {
	if v := firstEnv("VIGILO_TS_HOSTNAME", "TS_HOSTNAME"); v != "" {
		if hostnameRe.MatchString(v) {
			o.TailscaleHostname = v
		} else {
			log.Printf("VIGILO_TS_HOSTNAME non valido (solo a-z, 0-9 e '-', max 40): uso %q", o.TailscaleHostname)
		}
	}
	if v := firstEnv("VIGILO_TS_AUTHKEY", "TS_AUTHKEY"); v != "" {
		o.TailscaleAuthKey = v
	}
	if v := firstEnv("VIGILO_MQTT_HOST", "MQTT_HOST"); v != "" {
		o.MQTTHost = v
	}
	if v := firstEnv("VIGILO_MQTT_PORT", "MQTT_PORT"); v != "" {
		if p, err := strconv.Atoi(v); err == nil && p > 0 && p < 65536 {
			o.MQTTPort = p
		}
	}
	if v := firstEnv("VIGILO_MQTT_USERNAME", "MQTT_USERNAME"); v != "" {
		o.MQTTUsername = v
	}
	if v := firstEnv("VIGILO_MQTT_PASSWORD", "MQTT_PASSWORD"); v != "" {
		o.MQTTPassword = v
	}
}

// standaloneRegion: regione Tesla iniziale (poi si cambia dalla procedura guidata).
func standaloneRegion() string {
	switch strings.ToLower(firstEnv("VIGILO_REGION")) {
	case "na":
		return "na"
	}
	return "eu"
}

// ---------- broker MQTT interno ----------

// LocalBroker avvia mosquitto in ascolto solo su 127.0.0.1: serve a fleet-telemetry per passare
// i dati a Vigilo. Nessuna porta esposta fuori dal container.
type LocalBroker struct {
	bin     string
	port    int
	mu      sync.Mutex
	cmd     *exec.Cmd
	exited  chan struct{}
	lastErr string
}

func (b *LocalBroker) service() *MQTTService {
	return &MQTTService{Host: "127.0.0.1", Port: b.port}
}

func (b *LocalBroker) running() bool {
	if b.cmd == nil || b.exited == nil {
		return false
	}
	select {
	case <-b.exited:
		return false
	default:
		return true
	}
}

// Ensure avvia il broker se non è attivo e aspetta che accetti connessioni.
func (b *LocalBroker) Ensure(ctx context.Context) (*MQTTService, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.running() {
		return b.service(), nil
	}
	if _, err := os.Stat(b.bin); err != nil {
		return nil, fmt.Errorf("broker MQTT interno non trovato (%s)", b.bin)
	}
	conf := filepath.Join(os.TempDir(), "vigilo-mosquitto.conf")
	cfg := fmt.Sprintf("listener %d 127.0.0.1\nallow_anonymous true\npersistence false\nlog_dest stderr\nlog_type error\nlog_type warning\n", b.port)
	if err := os.WriteFile(conf, []byte(cfg), 0o644); err != nil {
		return nil, err
	}
	cmd := exec.Command(b.bin, "-c", conf)
	cmd.Stdout = prefixWriter("mosquitto")
	cmd.Stderr = prefixWriter("mosquitto")
	if err := cmd.Start(); err != nil {
		b.lastErr = err.Error()
		return nil, fmt.Errorf("avvio broker MQTT interno: %w", err)
	}
	exited := make(chan struct{})
	b.cmd, b.exited = cmd, exited
	go func() {
		err := cmd.Wait()
		close(exited)
		log.Printf("broker MQTT interno terminato: %v", err)
	}()
	addr := fmt.Sprintf("127.0.0.1:%d", b.port)
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, time.Second)
		if err == nil {
			c.Close()
			log.Printf("broker MQTT interno avviato su %s", addr)
			return b.service(), nil
		}
		select {
		case <-exited:
			return nil, errors.New("il broker MQTT interno si è chiuso subito: guarda le righe «mosquitto:» nel registro")
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
	return nil, errors.New("il broker MQTT interno non risponde")
}

// ---------- ntfy (facoltativo) ----------

// NtfyConfig: push istantaneo tramite ntfy (ntfy.sh gratuito o server proprio). Vuoto = disattivato.
type NtfyConfig struct {
	Server string `json:"server"`
	Topic  string `json:"topic"`
	Token  string `json:"token,omitempty"`
}

func (c *NtfyConfig) enabled() bool { return c != nil && c.Server != "" && c.Topic != "" }

var ntfyTopicRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func normalizeNtfy(in NtfyConfig) (NtfyConfig, error) {
	in.Server = strings.TrimRight(strings.TrimSpace(in.Server), "/")
	in.Topic = strings.TrimSpace(in.Topic)
	in.Token = strings.TrimSpace(in.Token)
	if in.Topic == "" {
		return NtfyConfig{}, nil // disattivato
	}
	if in.Server == "" {
		in.Server = "https://ntfy.sh"
	}
	u, err := url.Parse(in.Server)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return in, errors.New("indirizzo del server ntfy non valido (es. https://ntfy.sh)")
	}
	if !ntfyTopicRe.MatchString(in.Topic) {
		return in, errors.New("argomento ntfy non valido: solo lettere, numeri, '-' e '_' (max 64)")
	}
	return in, nil
}

var ntfyClient = &http.Client{Timeout: 15 * time.Second}

// sendNtfy pubblica in formato JSON (titolo e testo UTF-8, emoji comprese).
func sendNtfy(ctx context.Context, c NtfyConfig, title, msg string, priority int, tags []string) error {
	m := map[string]any{"topic": c.Topic, "title": title, "message": msg, "priority": priority}
	if len(tags) > 0 {
		m["tags"] = tags
	}
	body, _ := json.Marshal(m)
	req, err := http.NewRequestWithContext(ctx, "POST", c.Server, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := ntfyClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	if resp.StatusCode >= 300 {
		return fmt.Errorf("ntfy: HTTP %d %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}

// ntfyPriority traduce i dati della notifica di Home Assistant in priorità ntfy.
func ntfyPriority(data map[string]any) (int, []string) {
	switch fmt.Sprint(data["tag"]) {
	case "vigilo-allarme":
		return 5, []string{"rotating_light"}
	case "vigilo-sentinella":
		return 4, []string{"warning"}
	case "vigilo-salute":
		return 3, []string{"wrench"}
	}
	return 3, nil
}

func (a *App) notifyStandalone(ctx context.Context, title, msg string, data map[string]any) {
	c := a.store.Get().Ntfy
	if !c.enabled() {
		return // l'app Vigilo legge comunque l'evento dall'API
	}
	p, tags := ntfyPriority(data)
	if err := sendNtfy(ctx, *c, title, msg, p, tags); err != nil {
		log.Printf("notifica ntfy non riuscita: %v", err)
	}
}

// ---------- password dell'interfaccia ----------

// loadUIPassword: VIGILO_UI_PASSWORD se impostata, altrimenti una generata alla prima avvio e
// salvata in /data/ui_password (0600). Restituisce anche la fonte da mostrare nel registro (mai il valore).
func loadUIPassword(dataDir string) (pw, source string, err error) {
	if v := os.Getenv("VIGILO_UI_PASSWORD"); strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v), "variabile VIGILO_UI_PASSWORD", nil
	}
	path := filepath.Join(dataDir, "ui_password")
	if b, err := os.ReadFile(path); err == nil && strings.TrimSpace(string(b)) != "" {
		return strings.TrimSpace(string(b)), path, nil
	}
	pw = randomToken(12)
	if err := os.WriteFile(path, []byte(pw+"\n"), 0o600); err != nil {
		return "", "", err
	}
	return pw, path + " (appena creata)", nil
}

// ---------- interfaccia web standalone ----------

// StandaloneHandler: la stessa procedura guidata dell'Ingress, protetta da password, con il passo 6
// (notifiche) adattato. /healthz è libero per il controllo di salute di Docker.
func (a *App) StandaloneHandler(password string) http.Handler {
	inner := a.ingressMux()
	mux := http.NewServeMux()
	page := standalonePage()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(page)
	})
	mux.HandleFunc("GET /ui/ntfy", func(w http.ResponseWriter, r *http.Request) {
		c := a.store.Get().Ntfy
		out := map[string]any{"ok": true, "server": "", "topic": "", "has_token": false}
		if c != nil {
			out["server"], out["topic"], out["has_token"] = c.Server, c.Topic, c.Token != ""
		}
		writeJSON(w, 200, out)
	})
	mux.HandleFunc("POST /ui/ntfy", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			NtfyConfig
			ClearToken bool `json:"clear_token"`
		}
		if err := readJSON(r, &in); err != nil {
			errJSON(w, 400, err)
			return
		}
		old := a.store.Get().Ntfy
		if in.Token == "" && !in.ClearToken && old != nil {
			in.Token = old.Token // campo lasciato vuoto: si tiene il token salvato
		}
		c, err := normalizeNtfy(in.NtfyConfig)
		if err != nil {
			errJSON(w, 400, err)
			return
		}
		_ = a.store.Update(func(s *State) {
			if c.enabled() {
				s.Ntfy = &c
			} else {
				s.Ntfy = nil
			}
		})
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /ui/test-notify", func(w http.ResponseWriter, r *http.Request) {
		c := a.store.Get().Ntfy
		if !c.enabled() {
			errJSON(w, 400, errString("ntfy non configurato: inserisci almeno l'argomento e salva"))
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
		defer cancel()
		if err := sendNtfy(ctx, *c, "Vigilo", "Notifica di prova: funziona!", 3, nil); err != nil {
			errJSON(w, 502, err)
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.Handle("/", inner)

	protected := securityHeaders(requireUIPassword(password, sameOrigin(mux)))
	root := http.NewServeMux()
	root.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true, "version": a.version})
	})
	root.Handle("/", protected)
	return root
}

// requireUIPassword: autenticazione HTTP Basic (utente "vigilo"). Basta per una rete di casa;
// non esporre la porta su internet.
func requireUIPassword(password string, h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, p, ok := r.BasicAuth()
		if !ok || subtle.ConstantTimeCompare([]byte(p), []byte(password)) != 1 {
			if ok {
				time.Sleep(time.Second) // rallenta i tentativi
			}
			w.Header().Set("WWW-Authenticate", `Basic realm="Vigilo", charset="UTF-8"`)
			http.Error(w, "accesso richiesto: utente vigilo e la password di Vigilo", http.StatusUnauthorized)
			return
		}
		h.ServeHTTP(w, r)
	})
}

// sameOrigin blocca le richieste di modifica partite da altri siti (il browser invierebbe la password da solo).
func sameOrigin(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			if o := r.Header.Get("Origin"); o != "" {
				u, err := url.Parse(o)
				if err != nil || !strings.EqualFold(u.Host, r.Host) {
					http.Error(w, "richiesta da un altro sito rifiutata", http.StatusForbidden)
					return
				}
			} else if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
				http.Error(w, "richiesta da un altro sito rifiutata", http.StatusForbidden)
				return
			}
		}
		h.ServeHTTP(w, r)
	})
}

// healthcheck per HEALTHCHECK di Docker: nessun curl nell'immagine, lo fa il binario stesso.
func healthcheck(u string) int {
	c := &http.Client{Timeout: 5 * time.Second}
	resp, err := c.Get(u)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		fmt.Fprintln(os.Stderr, "HTTP", resp.StatusCode)
		return 1
	}
	return 0
}
