package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// Ogni testo che la pagina standalone sostituisce deve esistere ancora in index.html: se qualcuno
// cambia la pagina del componente aggiuntivo, questo test lo segnala invece di lasciare testi sbagliati.
func TestStandalonePage(t *testing.T) {
	orig, err := webFS.ReadFile("web/index.html")
	if err != nil {
		t.Fatal(err)
	}
	o := string(orig)
	for _, sw := range standaloneTextSwaps {
		if strings.Count(o, sw[0]) != 1 {
			t.Errorf("testo da sostituire non trovato (o ripetuto) in index.html: %q", sw[0])
		}
	}
	if n := len(step6Re.FindAllString(o, -1)); n != 1 {
		t.Fatalf("sezione s6 trovata %d volte", n)
	}
	p := string(standalonePage())
	for _, want := range []string{`id="ntopic"`, `id="naware"`, `id="npanic"`, `id="nprob"`, `id="thr"`, `id="targets"`, "loadHA=load", "versione Docker"} {
		if !strings.Contains(p, want) {
			t.Errorf("manca %q nella pagina standalone", want)
		}
	}
	if strings.Contains(p, "Scegli i telefoni (app Home Assistant)") {
		t.Error("il passo 6 di Home Assistant è rimasto nella pagina standalone")
	}
	if strings.Count(p, "</body>") != 1 || strings.Index(p, "loadHA=load") > strings.Index(p, "</body>") {
		t.Error("script standalone fuori posto")
	}
}

func newTestApp(t *testing.T, standalone bool) *App {
	t.Helper()
	dir := t.TempDir()
	st, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	tesla, err := NewTesla(st, dir)
	if err != nil {
		t.Fatal(err)
	}
	a := &App{store: st, events: NewEventLog(dir), tesla: tesla, tailnet: NewTailnet(dir, "vigilo", "", telemetryListen),
		ha: &HA{client: http.DefaultClient}, certs: Certs{dir: filepath.Join(dir, "tls")}, version: "test", standalone: standalone}
	a.ft = &FleetTelemetry{bin: "/nonexistent", dir: filepath.Join(dir, "ft"), certs: a.certs}
	a.bus = &Bus{app: a}
	return a
}

func TestStandaloneHandlerAuth(t *testing.T) {
	a := newTestApp(t, true)
	h := a.StandaloneHandler("segreta-di-prova")
	do := func(method, path, pw, origin string) int {
		req := httptest.NewRequest(method, "http://server:8099"+path, strings.NewReader("{}"))
		if pw != "" {
			req.SetBasicAuth("vigilo", pw)
		}
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}
	if c := do("GET", "/healthz", "", ""); c != 200 {
		t.Fatalf("/healthz senza password: %d", c)
	}
	if c := do("GET", "/", "", ""); c != 401 {
		t.Fatalf("pagina senza password: %d", c)
	}
	if c := do("GET", "/ui/state", "", ""); c != 401 {
		t.Fatalf("/ui/state senza password: %d", c)
	}
	if c := do("GET", "/api/v1/status", "", ""); c != 401 {
		t.Fatalf("/api/v1/status senza password: %d", c)
	}
	if c := do("GET", "/", "sbagliata", ""); c != 401 {
		t.Fatalf("password sbagliata: %d", c)
	}
	if c := do("GET", "/", "segreta-di-prova", ""); c != 200 {
		t.Fatalf("pagina con password: %d", c)
	}
	if c := do("GET", "/ui/state", "segreta-di-prova", ""); c != 200 {
		t.Fatalf("/ui/state con password: %d", c)
	}
	if c := do("POST", "/ui/pairing/reset", "segreta-di-prova", "https://sito-cattivo.example"); c != 403 {
		t.Fatalf("POST da altro sito: %d", c)
	}
	if c := do("POST", "/ui/pairing/reset", "segreta-di-prova", "http://server:8099"); c != 200 {
		t.Fatalf("POST dallo stesso sito: %d", c)
	}
}

// In modalità componente aggiuntivo l'interfaccia resta solo per l'Ingress e la pagina è index.html così com'è.
func TestHAModeUnchanged(t *testing.T) {
	a := newTestApp(t, false)
	h := a.IngressHandler()
	req := httptest.NewRequest("GET", "/", nil)
	req.RemoteAddr = "172.30.32.2:1234"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	orig, _ := webFS.ReadFile("web/index.html")
	if rec.Code != 200 || rec.Body.String() != string(orig) {
		t.Fatal("la pagina Ingress deve essere index.html identico")
	}
	req = httptest.NewRequest("GET", "/ui/state", nil)
	req.RemoteAddr = "192.0.2.50:1234"
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 403 {
		t.Fatalf("Ingress da IP non ammesso: %d", rec.Code)
	}
	// Nessun campo ntfy nello stato salvato del componente aggiuntivo.
	_ = a.store.Update(func(s *State) {})
	b, _ := os.ReadFile(a.store.path)
	if strings.Contains(string(b), "ntfy") {
		t.Fatal("state.json del componente aggiuntivo non deve contenere ntfy")
	}
}

func TestStandaloneNtfy(t *testing.T) {
	var mu sync.Mutex
	var got []map[string]any
	var auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		mu.Lock()
		got = append(got, m)
		auth = r.Header.Get("Authorization")
		mu.Unlock()
		w.WriteHeader(200)
	}))
	defer srv.Close()

	a := newTestApp(t, true)
	h := a.StandaloneHandler("pw")
	post := func(path, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "http://s"+path, strings.NewReader(body))
		req.SetBasicAuth("vigilo", "pw")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}
	if rec := post("/ui/test-notify", "{}"); rec.Code != 400 {
		t.Fatalf("prova senza ntfy: %d", rec.Code)
	}
	if rec := post("/ui/ntfy", `{"server":"ftp://x","topic":"abc"}`); rec.Code != 400 {
		t.Fatalf("server non valido accettato: %d", rec.Code)
	}
	if rec := post("/ui/ntfy", `{"server":"`+srv.URL+`/","topic":"vigilo-test_1","token":"tk-123"}`); rec.Code != 200 {
		t.Fatalf("salvataggio ntfy: %d %s", rec.Code, rec.Body)
	}
	// Il token non torna mai indietro.
	req := httptest.NewRequest("GET", "http://s/ui/ntfy", nil)
	req.SetBasicAuth("vigilo", "pw")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if strings.Contains(rec.Body.String(), "tk-123") || !strings.Contains(rec.Body.String(), `"has_token":true`) {
		t.Fatalf("GET /ui/ntfy: %s", rec.Body)
	}
	// Salvare le impostazioni dall'app (PUT /api/v1/settings) non deve cancellare ntfy.
	a.saveSettings(Settings{NotifyAware: true, NotifyPanic: true, AwareThrottleSec: 0})
	// Risalvare senza token mantiene quello salvato.
	if rec := post("/ui/ntfy", `{"server":"`+srv.URL+`","topic":"vigilo-test_1"}`); rec.Code != 200 {
		t.Fatalf("risalvataggio: %d", rec.Code)
	}
	if rec := post("/ui/test-notify", "{}"); rec.Code != 200 {
		t.Fatalf("notifica di prova: %d %s", rec.Code, rec.Body)
	}
	a.onSentry("Armed", "Aware")
	a.onSentry("Aware", "Panic")
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 3 {
		t.Fatalf("attese 3 notifiche ntfy, arrivate %d", len(got))
	}
	if auth != "Bearer tk-123" {
		t.Fatal("token ntfy non inviato")
	}
	if got[1]["topic"] != "vigilo-test_1" || got[1]["priority"].(float64) != 4 {
		t.Fatalf("Aware: %v", got[1])
	}
	if got[2]["priority"].(float64) != 5 || !strings.Contains(got[2]["title"].(string), "ALLARME") {
		t.Fatalf("Panic: %v", got[2])
	}
	// Gli eventi per l'app ci sono comunque.
	ev := a.events.List(0, 10)
	types := map[string]bool{}
	for _, e := range ev {
		types[e.Type] = true
	}
	if !types[EvAware] || !types[EvPanic] {
		t.Fatalf("eventi mancanti: %v", ev)
	}
	// Argomento vuoto = ntfy disattivato.
	if rec := post("/ui/ntfy", `{"server":"","topic":""}`); rec.Code != 200 || a.store.Get().Ntfy != nil {
		t.Fatal("disattivazione ntfy non riuscita")
	}
}

// L'app abbinata usa le stesse API anche in modalità standalone (token di abbinamento sul server pubblico).
func TestStandalonePublicAPI(t *testing.T) {
	a := newTestApp(t, true)
	h := a.PublicHandler()
	tok := a.store.Get().PairingToken
	for _, path := range []string{"/api/v1/status", "/api/v1/events?after=0", "/api/v1/settings", "/api/v1/health"} {
		req := httptest.NewRequest("GET", path, nil)
		req.Header.Set("Authorization", "Bearer "+tok)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"ok":true`) {
			t.Fatalf("%s: %d %s", path, rec.Code, rec.Body)
		}
		if strings.Contains(rec.Body.String(), "null,") && path == "/api/v1/settings" {
			t.Fatalf("liste null nelle impostazioni (l'app vuole liste): %s", rec.Body)
		}
		if strings.Contains(rec.Body.String(), "targets_error") {
			t.Fatalf("%s: errore Home Assistant in modalità standalone: %s", path, rec.Body)
		}
	}
}

func TestLocalBroker(t *testing.T) {
	bin := os.Getenv("VIGILO_MOSQUITTO")
	if bin == "" {
		t.Skip("imposta VIGILO_MOSQUITTO=/usr/sbin/mosquitto")
	}
	b := &LocalBroker{bin: bin, port: 21883}
	m, err := b.Ensure(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer b.cmd.Process.Kill()
	if m.Host != "127.0.0.1" || m.Port != 21883 {
		t.Fatalf("servizio: %+v", m)
	}
	if _, err := b.Ensure(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestApplyStandaloneEnv(t *testing.T) {
	t.Setenv("VIGILO_TS_HOSTNAME", "Vigilo Docker!") // non valido: resta il predefinito
	t.Setenv("TS_AUTHKEY", "tskey-test")
	t.Setenv("MQTT_HOST", "broker.lan")
	t.Setenv("MQTT_PORT", "1884")
	o := Options{TailscaleHostname: "vigilo"}
	applyStandaloneEnv(&o)
	if o.TailscaleHostname != "vigilo" || o.TailscaleAuthKey != "tskey-test" || o.MQTTHost != "broker.lan" || o.MQTTPort != 1884 {
		t.Fatalf("%+v", o)
	}
	t.Setenv("VIGILO_TS_HOSTNAME", "vigilo-docker")
	applyStandaloneEnv(&o)
	if o.TailscaleHostname != "vigilo-docker" {
		t.Fatal(o.TailscaleHostname)
	}
}

func TestUIPasswordPersists(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("VIGILO_UI_PASSWORD", "")
	p1, _, err := loadUIPassword(dir)
	if err != nil || len(p1) < 12 {
		t.Fatal(err, p1)
	}
	p2, _, _ := loadUIPassword(dir)
	if p1 != p2 {
		t.Fatal("la password generata deve restare uguale tra un riavvio e l'altro")
	}
	fi, _ := os.Stat(filepath.Join(dir, "ui_password"))
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("permessi %v", fi.Mode().Perm())
	}
	t.Setenv("VIGILO_UI_PASSWORD", "dalla-variabile")
	if p, _, _ := loadUIPassword(dir); p != "dalla-variabile" {
		t.Fatal(p)
	}
}
