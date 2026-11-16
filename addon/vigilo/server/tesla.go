package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/proxy"
	"github.com/teslamotors/vehicle-command/pkg/sign"
)

const (
	authorizeURL = "https://auth.tesla.com/oauth2/v3/authorize"
	tokenURL     = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"
	scopes       = "openid offline_access vehicle_device_data vehicle_location vehicle_cmds vehicle_charging_cmds"
	userAgent    = "Vigilo-HA-addon/1.0"
)

var regionHosts = map[string]string{
	"eu": "fleet-api.prd.eu.vn.cloud.tesla.com",
	"na": "fleet-api.prd.na.vn.cloud.tesla.com",
}

// Tesla raggruppa: login, token, chiave virtuale, comandi firmati (proxy ufficiale in-process) e Fleet API.
type Tesla struct {
	store   *Store
	dataDir string
	http    *http.Client

	mu       sync.Mutex
	key      protocol.ECDHPrivateKey
	pubPEM   []byte
	px       *proxy.Proxy
	states   map[string]time.Time // state OAuth in attesa -> scadenza
	verifier map[string]string
}

func NewTesla(store *Store, dataDir string) (*Tesla, error) {
	t := &Tesla{store: store, dataDir: dataDir, http: &http.Client{Timeout: 40 * time.Second},
		states: map[string]time.Time{}, verifier: map[string]string{}}
	if err := t.loadOrCreateKey(); err != nil {
		return nil, err
	}
	return t, nil
}

// ---------- chiave virtuale (P-256) ----------

func (t *Tesla) keyPath() string { return filepath.Join(t.dataDir, "private-key.pem") }

func (t *Tesla) loadOrCreateKey() error {
	if _, err := os.Stat(t.keyPath()); errors.Is(err, os.ErrNotExist) {
		k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return err
		}
		der, err := x509.MarshalECPrivateKey(k)
		if err != nil {
			return err
		}
		if err := os.WriteFile(t.keyPath(), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der}), 0o600); err != nil {
			return err
		}
		log.Printf("generata una nuova chiave virtuale")
	}
	key, err := protocol.LoadPrivateKey(t.keyPath())
	if err != nil {
		return fmt.Errorf("chiave privata non valida: %w", err)
	}
	raw, err := os.ReadFile(t.keyPath())
	if err != nil {
		return err
	}
	blk, _ := pem.Decode(raw)
	if blk == nil {
		return errors.New("PEM chiave non valido")
	}
	ek, err := x509.ParseECPrivateKey(blk.Bytes)
	if err != nil {
		return err
	}
	pubDER, err := x509.MarshalPKIXPublicKey(&ek.PublicKey)
	if err != nil {
		return err
	}
	px, err := proxy.New(context.Background(), key, 20)
	if err != nil {
		return err
	}
	px.Timeout = 30 * time.Second
	t.key, t.px = key, px
	t.pubPEM = pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: pubDER})
	return nil
}

func (t *Tesla) PublicKeyPEM() []byte { return t.pubPEM }

func (t *Tesla) PublicKeyFingerprint() string {
	h := sha256.Sum256(t.pubPEM)
	return fmt.Sprintf("%x", h[:6])
}

// ---------- OAuth ----------

func (t *Tesla) AuthorizeURL(redirect string) (string, error) {
	s := t.store.Get()
	if s.ClientID == "" {
		return "", errors.New("inserisci prima Client ID e Client Secret")
	}
	state := randomToken(16)
	verifier := randomToken(48)
	sum := sha256.Sum256([]byte(verifier))
	t.mu.Lock()
	t.states[state] = time.Now().Add(15 * time.Minute)
	t.verifier[state] = verifier
	t.mu.Unlock()
	q := url.Values{
		"response_type":         {"code"},
		"client_id":             {s.ClientID},
		"redirect_uri":          {redirect},
		"scope":                 {scopes},
		"state":                 {state},
		"code_challenge":        {base64.RawURLEncoding.EncodeToString(sum[:])},
		"code_challenge_method": {"S256"},
		"prompt_missing_scopes": {"true"},
	}
	return authorizeURL + "?" + q.Encode(), nil
}

func (t *Tesla) audience() string {
	r := t.store.Get().Region
	if h, ok := regionHosts[r]; ok {
		return "https://" + h
	}
	return "https://" + regionHosts["eu"]
}

// ExchangeCode completa il login. Verifica lo state per impedire richieste forgiate.
func (t *Tesla) ExchangeCode(ctx context.Context, code, state, redirect string) error {
	t.mu.Lock()
	exp, ok := t.states[state]
	verifier := t.verifier[state]
	delete(t.states, state)
	delete(t.verifier, state)
	t.mu.Unlock()
	if !ok || time.Now().After(exp) {
		return errors.New("richiesta di accesso scaduta o non valida: riprova da Home Assistant")
	}
	s := t.store.Get()
	form := url.Values{
		"grant_type":    {"authorization_code"},
		"client_id":     {s.ClientID},
		"client_secret": {s.ClientSecret},
		"code":          {code},
		"code_verifier": {verifier},
		"audience":      {t.audience()},
		"redirect_uri":  {redirect},
	}
	return t.tokenRequest(ctx, form)
}

func (t *Tesla) tokenRequest(ctx context.Context, form url.Values) error {
	req, _ := http.NewRequestWithContext(ctx, "POST", tokenURL, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := t.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var tr struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int64  `json:"expires_in"`
		Error        string `json:"error"`
		Desc         string `json:"error_description"`
	}
	_ = json.Unmarshal(body, &tr)
	if resp.StatusCode != 200 || tr.AccessToken == "" {
		return fmt.Errorf("Tesla ha rifiutato il token (%d): %s %s", resp.StatusCode, tr.Error, tr.Desc)
	}
	host := fleetHostFromToken(tr.AccessToken)
	return t.store.Update(func(s *State) {
		s.AccessToken = tr.AccessToken
		if tr.RefreshToken != "" {
			s.RefreshToken = tr.RefreshToken
		}
		s.AccessExp = nowUnix() + tr.ExpiresIn
		if host != "" {
			s.FleetHost = host
		}
	})
}

// Token restituisce un access token valido, rinnovandolo se scade entro 10 minuti.
func (t *Tesla) Token(ctx context.Context) (string, error) {
	s := t.store.Get()
	if s.RefreshToken == "" && s.AccessToken == "" {
		return "", errors.New("non hai ancora fatto l'accesso a Tesla")
	}
	if s.AccessToken != "" && s.AccessExp-nowUnix() > 600 {
		return s.AccessToken, nil
	}
	if s.RefreshToken == "" {
		return "", errors.New("accesso Tesla scaduto: rifai il login")
	}
	err := t.tokenRequest(ctx, url.Values{
		"grant_type":    {"refresh_token"},
		"client_id":     {s.ClientID},
		"refresh_token": {s.RefreshToken},
	})
	if err != nil {
		return "", fmt.Errorf("rinnovo del token non riuscito: %w", err)
	}
	return t.store.Get().AccessToken, nil
}

// fleetHostFromToken legge (senza verificare) aud e ou_code del JWT, come fa la libreria Tesla.
func fleetHostFromToken(tok string) string {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return ""
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		raw, err = base64.RawStdEncoding.DecodeString(parts[1])
		if err != nil {
			return ""
		}
	}
	var p struct {
		Aud    json.RawMessage `json:"aud"`
		OUCode string          `json:"ou_code"`
	}
	if json.Unmarshal(raw, &p) != nil {
		return ""
	}
	var auds []string
	if json.Unmarshal(p.Aud, &auds) != nil {
		var one string
		if json.Unmarshal(p.Aud, &one) == nil {
			auds = []string{one}
		}
	}
	if oc := strings.ToLower(p.OUCode); oc != "" {
		if h, ok := regionHosts[oc]; ok {
			return h
		}
	}
	for _, a := range auds {
		h := strings.TrimSuffix(strings.TrimPrefix(a, "https://"), "/")
		if strings.HasPrefix(h, "fleet-api.") && strings.HasSuffix(h, ".tesla.com") {
			return h
		}
	}
	return ""
}

// ---------- chiamate Fleet API (passano dal proxy ufficiale, che firma i comandi) ----------

type APIError struct {
	Code int
	Msg  string
}

func (e *APIError) Error() string { return fmt.Sprintf("%s (%d)", e.Msg, e.Code) }

func (t *Tesla) call(ctx context.Context, method, path string, body any) (map[string]any, error) {
	tok, err := t.Token(ctx)
	if err != nil {
		return nil, err
	}
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	viaProxy := strings.Contains(path, "/command/") ||
		(method == "POST" && strings.HasSuffix(path, "/fleet_telemetry_config"))
	var code int
	var respBody []byte
	target := "proxy"
	if viaProxy {
		// Comandi firmati e firma della configurazione: proxy ufficiale Tesla, nello stesso processo.
		req := httptest.NewRequest(method, "http://vigilo.local"+path, rd).WithContext(ctx)
		req.Header.Set("Authorization", "Bearer "+tok)
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		t.px.ServeHTTP(rec, req)
		code, respBody = rec.Code, rec.Body.Bytes()
	} else {
		host := t.fleetHost(tok)
		target = host
		req, err := http.NewRequestWithContext(ctx, method, "https://"+host+path, rd)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+tok)
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("User-Agent", userAgent)
		resp, err := t.http.Do(req)
		if err != nil {
			return nil, err
		}
		respBody, _ = io.ReadAll(io.LimitReader(resp.Body, 4<<20))
		resp.Body.Close()
		code = resp.StatusCode
	}
	var out map[string]any
	_ = json.Unmarshal(respBody, &out)
	if code >= 300 {
		rc := code
		msg := describeStatus(rc)
		detail := ""
		if out != nil {
			if e, _ := out["error"].(string); e != "" {
				detail = e
			}
			if d, _ := out["error_description"].(string); d != "" {
				detail = strings.TrimSpace(detail + " " + d)
			}
		}
		if detail == "" {
			detail = strings.TrimSpace(string(respBody))
		}
		detail = sanitizeForLog(detail, 200)
		if detail != "" {
			msg += ": " + detail
		}
		// Registro: metodo, host e percorso (VIN mascherato), stato e risposta ripulita. Mai i token.
		log.Printf("Fleet API %s %s%s -> HTTP %d %s", method, target, maskVINs(path), code, sanitizeForLog(string(respBody), 300))
		return out, &APIError{Code: rc, Msg: msg}
	}
	if out == nil {
		out = map[string]any{}
	}
	return out, nil
}

// fleetHost: server Fleet API della regione dell'account (salvato, dal token, o Europa).
func (t *Tesla) fleetHost(tok string) string {
	host := t.store.Get().FleetHost
	if host == "" {
		host = fleetHostFromToken(tok)
	}
	if host == "" {
		host = regionHosts["eu"]
	}
	return host
}

var (
	vinRe = regexp.MustCompile(`\b[A-HJ-NPR-Z0-9]{13}([0-9]{4})\b`)
	jwtRe = regexp.MustCompile(`eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*`)
)

// maskVINs sostituisce i VIN con ***1234.
func maskVINs(s string) string { return vinRe.ReplaceAllString(s, "***$1") }

// sanitizeForLog toglie token/JWS, maschera i VIN, compatta e tronca.
func sanitizeForLog(s string, n int) string {
	s = jwtRe.ReplaceAllString(s, "<token>")
	s = maskVINs(s)
	s = strings.Join(strings.Fields(s), " ")
	return truncate(s, n)
}

func describeStatus(code int) string {
	switch code {
	case 401:
		return "accesso Tesla non valido: rifai il login"
	case 403:
		return "accesso negato: controlla gli scope dell'app sul portale Tesla"
	case 408:
		return "auto addormentata o non raggiungibile"
	case 412:
		return "dominio non registrato o chiave non associata"
	case 404:
		return "auto o risorsa non trovata da Tesla"
	case 421:
		return "regione Tesla errata"
	case 429:
		return "troppe richieste, riprova più tardi"
	}
	return "errore Fleet API"
}

type VehicleInfo struct {
	VIN   string `json:"vin"`
	Name  string `json:"name"`
	State string `json:"state"`
}

func (t *Tesla) Vehicles(ctx context.Context) ([]VehicleInfo, error) {
	out, err := t.call(ctx, "GET", "/api/1/vehicles", nil)
	if err != nil {
		return nil, err
	}
	arr, _ := out["response"].([]any)
	var vs []VehicleInfo
	for _, it := range arr {
		m, _ := it.(map[string]any)
		vs = append(vs, VehicleInfo{VIN: str(m["vin"]), Name: str(m["display_name"]), State: str(m["state"])})
	}
	return vs, nil
}

// ConnectionState non sveglia l'auto.
func (t *Tesla) ConnectionState(ctx context.Context, vin string) (string, error) {
	out, err := t.call(ctx, "GET", "/api/1/vehicles/"+vin, nil)
	if err != nil {
		return "", err
	}
	m, _ := out["response"].(map[string]any)
	return str(m["state"]), nil
}

func (t *Tesla) WakeAndWait(ctx context.Context, vin string) error {
	if st, err := t.ConnectionState(ctx, vin); err == nil && st == "online" {
		return nil
	}
	_, _ = t.call(ctx, "POST", "/api/1/vehicles/"+vin+"/wake_up", map[string]any{})
	deadline := time.Now().Add(50 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(3 * time.Second):
		}
		if st, err := t.ConnectionState(ctx, vin); err == nil && st == "online" {
			return nil
		}
	}
	return errors.New("l'auto non si è svegliata: potrebbe avere poco segnale")
}

var commandMap = map[string]struct {
	path string
	body map[string]any
	ok   string
}{
	"sentry_on":  {"set_sentry_mode", map[string]any{"on": true}, "Sentinella attivata"},
	"sentry_off": {"set_sentry_mode", map[string]any{"on": false}, "Sentinella disattivata"},
	"lock":       {"door_lock", nil, "Auto chiusa"},
	"unlock":     {"door_unlock", nil, "Auto aperta"},
	"honk":       {"honk_horn", nil, "Clacson suonato"},
	"flash":      {"flash_lights", nil, "Fari lampeggiati"},
}

// Command invia un comando firmato; se l'auto dorme la sveglia e riprova.
func (t *Tesla) Command(ctx context.Context, vin, name string) (string, error) {
	c, ok := commandMap[name]
	if !ok {
		return "", fmt.Errorf("comando sconosciuto: %s", name)
	}
	body := c.body
	if body == nil {
		body = map[string]any{}
	}
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		out, err := t.call(ctx, "POST", "/api/1/vehicles/"+vin+"/command/"+c.path, body)
		if err == nil {
			r, _ := out["response"].(map[string]any)
			if r != nil {
				if res, ok := r["result"].(bool); ok && !res {
					return "", fmt.Errorf("l'auto ha rifiutato il comando: %s", str(r["reason"]))
				}
			}
			return c.ok, nil
		}
		lastErr = err
		var ae *APIError
		transient := errors.As(err, &ae) && (ae.Code == 408 || ae.Code == 500 || ae.Code == 503)
		if !transient {
			return "", err
		}
		if werr := t.WakeAndWait(ctx, vin); werr != nil {
			return "", werr
		}
	}
	return "", lastErr
}

// VehicleData legge lo stato completo SOLO se l'auto è già online (nessun risveglio).
func (t *Tesla) VehicleData(ctx context.Context, vin string, v *VehicleState) error {
	st, err := t.ConnectionState(ctx, vin)
	if err != nil {
		return err
	}
	v.ConnState = st
	v.Online = st == "online"
	if !v.Online {
		return nil
	}
	ep := url.QueryEscape("charge_state;drive_state;location_data;vehicle_state")
	out, err := t.call(ctx, "GET", "/api/1/vehicles/"+vin+"/vehicle_data?endpoints="+ep, nil)
	if err != nil {
		return err
	}
	r, _ := out["response"].(map[string]any)
	cs, _ := r["charge_state"].(map[string]any)
	ds, _ := r["drive_state"].(map[string]any)
	vs, _ := r["vehicle_state"].(map[string]any)
	if b, ok := num(cs["battery_level"]); ok {
		v.Battery = &b
	}
	if m, ok := num(cs["battery_range"]); ok {
		km := m * 1.609344
		v.RangeKm = &km
	}
	if s := str(cs["charging_state"]); s != "" {
		v.Charging = s
	}
	if la, ok := num(ds["latitude"]); ok {
		if lo, ok2 := num(ds["longitude"]); ok2 {
			v.Lat, v.Lon = &la, &lo
		}
	}
	if g := str(ds["shift_state"]); g != "" {
		v.Gear = g
	} else {
		v.Gear = "P"
	}
	if b, ok := vs["locked"].(bool); ok {
		v.Locked = &b
	}
	if b, ok := vs["sentry_mode"].(bool); ok {
		if !b {
			v.Sentry = "Off"
		} else if v.Sentry == "" || v.Sentry == "Off" {
			v.Sentry = "Armed"
		}
	}
	doors := false
	for _, k := range []string{"df", "dr", "pf", "pr", "ft", "rt"} {
		if n, ok := num(vs[k]); ok && n != 0 {
			doors = true
		}
	}
	v.DoorsOpen = &doors
	win := false
	for _, k := range []string{"fd_window", "fp_window", "rd_window", "rp_window"} {
		if n, ok := num(vs[k]); ok && n != 0 {
			win = true
		}
	}
	v.WindowsOpen = &win
	v.UpdatedAt = time.Now().UnixMilli()
	return nil
}

// ---------- registrazione partner e telemetria ----------

// RegisterPartner registra il dominio presso Tesla (serve il Client Secret; la chiave pubblica deve
// essere già raggiungibile su https://<dominio>/.well-known/appspecific/com.tesla.3p.public-key.pem).
func (t *Tesla) RegisterPartner(ctx context.Context, domain string) error {
	s := t.store.Get()
	if s.ClientID == "" || s.ClientSecret == "" {
		return errors.New("mancano Client ID o Client Secret")
	}
	hosts := []string{regionHosts["eu"], regionHosts["na"]}
	var errs []string
	okAny := false
	for _, host := range hosts {
		form := url.Values{
			"grant_type":    {"client_credentials"},
			"client_id":     {s.ClientID},
			"client_secret": {s.ClientSecret},
			"scope":         {"openid vehicle_device_data vehicle_cmds vehicle_charging_cmds"},
			"audience":      {"https://" + host},
		}
		req, _ := http.NewRequestWithContext(ctx, "POST", tokenURL, strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		resp, err := t.http.Do(req)
		if err != nil {
			errs = append(errs, err.Error())
			continue
		}
		var tr struct {
			AccessToken string `json:"access_token"`
			Error       string `json:"error"`
		}
		_ = json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&tr)
		resp.Body.Close()
		if tr.AccessToken == "" {
			log.Printf("token partner per %s non ottenuto: %s", host, sanitizeForLog(tr.Error, 200))
			errs = append(errs, fmt.Sprintf("token partner (%s): %s", host, tr.Error))
			continue
		}
		b, _ := json.Marshal(map[string]string{"domain": domain})
		req2, _ := http.NewRequestWithContext(ctx, "POST", "https://"+host+"/api/1/partner_accounts", bytes.NewReader(b))
		req2.Header.Set("Authorization", "Bearer "+tr.AccessToken)
		req2.Header.Set("Content-Type", "application/json")
		resp2, err := t.http.Do(req2)
		if err != nil {
			errs = append(errs, err.Error())
			continue
		}
		body, _ := io.ReadAll(io.LimitReader(resp2.Body, 1<<16))
		resp2.Body.Close()
		if resp2.StatusCode >= 300 {
			log.Printf("registrazione dominio %s su %s: HTTP %d %s", domain, host, resp2.StatusCode, sanitizeForLog(string(body), 300))
			errs = append(errs, fmt.Sprintf("%s: %d %s", host, resp2.StatusCode, sanitizeForLog(string(body), 200)))
			continue
		}
		log.Printf("registrazione dominio %s su %s: OK", domain, host)
		okAny = true
	}
	if !okAny {
		return fmt.Errorf("registrazione del dominio non riuscita: %s", strings.Join(errs, "; "))
	}
	return t.store.Update(func(s *State) { s.PartnerDomain = domain })
}

type TelemetryStatus struct {
	Synced    *bool  `json:"synced"`
	KeyPaired *bool  `json:"key_paired"`
	Hostname  string `json:"hostname"`
	Exp       int64  `json:"exp"`
	Missing   bool   `json:"missing"`
}

// TelemetryConfig legge la configurazione dai server Tesla (non sveglia l'auto).
func (t *Tesla) TelemetryConfig(ctx context.Context, vin string) (TelemetryStatus, error) {
	out, err := t.call(ctx, "GET", "/api/1/vehicles/"+vin+"/fleet_telemetry_config", nil)
	var ts TelemetryStatus
	if err != nil {
		var ae *APIError
		if errors.As(err, &ae) && ae.Code == 404 {
			ts.Missing = true
			return ts, nil
		}
		return ts, err
	}
	r, _ := out["response"].(map[string]any)
	if b, ok := r["synced"].(bool); ok {
		ts.Synced = &b
	}
	if b, ok := r["key_paired"].(bool); ok {
		ts.KeyPaired = &b
	}
	cfg, _ := r["config"].(map[string]any)
	if cfg == nil {
		ts.Missing = true
		return ts, nil
	}
	ts.Hostname = str(cfg["hostname"])
	if e, ok := num(cfg["exp"]); ok {
		ts.Exp = int64(e)
	}
	return ts, nil
}

var telemetryFields = map[string]any{
	"SentryMode":          map[string]any{"interval_seconds": 1},
	"Locked":              map[string]any{"interval_seconds": 1},
	"DoorState":           map[string]any{"interval_seconds": 1},
	"FdWindow":            map[string]any{"interval_seconds": 5},
	"FpWindow":            map[string]any{"interval_seconds": 5},
	"RdWindow":            map[string]any{"interval_seconds": 5},
	"RpWindow":            map[string]any{"interval_seconds": 5},
	"Gear":                map[string]any{"interval_seconds": 5},
	"Location":            map[string]any{"interval_seconds": 60},
	"Soc":                 map[string]any{"interval_seconds": 300},
	"EstBatteryRange":     map[string]any{"interval_seconds": 300},
	"DetailedChargeState": map[string]any{"interval_seconds": 60},
}

// SendTelemetryConfig firma (Schnorr/P-256, come tesla-jws) e invia la configurazione: l'auto la riceve
// alla prossima connessione, quindi NON serve svegliarla.
func (t *Tesla) SendTelemetryConfig(ctx context.Context, vin, host string, port int, caPEM string) (string, error) {
	exp := time.Now().Add(364 * 24 * time.Hour).Unix()
	config := jwt.MapClaims{
		"hostname":    host,
		"port":        port,
		"ca":          caPEM,
		"exp":         exp,
		"alert_types": []string{"service"},
		"fields":      telemetryFields,
	}
	// Firma locale (Schnorr/P-256, identica al proxy ufficiale e a tesla-jws) e invio diretto a
	// /api/1/vehicles/fleet_telemetry_config_jws sullo stesso server regionale usato per le altre chiamate.
	jws, err := sign.SignMessageForFleet(t.key, "TelemetryClient", config)
	if err != nil {
		return "", fmt.Errorf("firma della configurazione: %w", err)
	}
	body := map[string]any{"vins": []string{vin}, "token": jws}
	out, err := t.call(ctx, "POST", "/api/1/vehicles/fleet_telemetry_config_jws", body)
	var ae *APIError
	if errors.As(err, &ae) && ae.Code == 404 {
		// Diagnosi: l'auto è nell'elenco di questo account/regione?
		if diag := t.diagnoseVehicle(ctx, vin); diag != "" {
			return "", fmt.Errorf("%s (%s)", err.Error(), diag)
		}
	}
	if err != nil {
		return "", err
	}
	r, _ := out["response"].(map[string]any)
	updated, _ := num(r["updated_vehicles"])
	skipped, _ := r["skipped_vehicles"].(map[string]any)
	var reasons []string
	for k, v := range skipped {
		if arr, ok := v.([]any); ok && len(arr) > 0 {
			reasons = append(reasons, describeSkip(k))
		}
	}
	if updated < 1 && len(reasons) > 0 {
		if containsStr(reasons, describeSkip("missing_key")) {
			f := false
			_ = t.store.Update(func(s *State) { s.KeyPaired = &f })
		}
		return "", fmt.Errorf("configurazione non applicata: %s", strings.Join(reasons, "; "))
	}
	_ = t.store.Update(func(s *State) {
		s.TelemetryExp = exp
		s.TelemetryHost = host
		s.TelemetrySent = nowUnix()
	})
	return "Configurazione inviata: l'auto la riceve alla prossima connessione.", nil
}

// diagnoseVehicle spiega un 404: auto assente da questo account o dalla regione del server.
func (t *Tesla) diagnoseVehicle(ctx context.Context, vin string) string {
	tok, err := t.Token(ctx)
	if err != nil {
		return ""
	}
	host := t.fleetHost(tok)
	vs, err := t.Vehicles(ctx)
	if err != nil {
		return "elenco auto non leggibile: " + err.Error()
	}
	for _, v := range vs {
		if v.VIN == vin {
			log.Printf("diagnosi 404: auto %s presente su %s (%d auto nell'account)", maskVINs(vin), host, len(vs))
			return "l'auto è nell'account su " + host + ": riprova tra qualche minuto o rifai l'abbinamento della chiave"
		}
	}
	log.Printf("diagnosi 404: auto %s NON presente su %s (%d auto nell'account)", maskVINs(vin), host, len(vs))
	return fmt.Sprintf("l'auto non risulta nell'account Tesla collegato su %s (%d auto trovate): rifai \"Accedi con Tesla\" con l'account proprietario dell'auto", host, len(vs))
}

func describeSkip(k string) string {
	switch k {
	case "missing_key":
		return "chiave virtuale non associata all'auto"
	case "unsupported_firmware":
		return "software dell'auto da aggiornare"
	case "unsupported_hardware":
		return "auto non compatibile"
	case "max_configs":
		return "troppe app di telemetria configurate sull'auto"
	}
	return k
}

// ---------- helper ----------

func str(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func num(v any) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case int:
		return float64(n), true
	case int64:
		return float64(n), true
	case json.Number:
		f, err := n.Float64()
		return f, err == nil
	case string:
		var f float64
		_, err := fmt.Sscanf(n, "%g", &f)
		return f, err == nil
	}
	return 0, false
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}

func containsStr(a []string, s string) bool {
	for _, x := range a {
		if x == s {
			return true
		}
	}
	return false
}
