package main

import (
	"context"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"html"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

//go:embed web/*
var webFS embed.FS

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func errJSON(w http.ResponseWriter, code int, err error) {
	writeJSON(w, code, map[string]any{"ok": false, "error": err.Error()})
}

func readJSON(r *http.Request, v any) error {
	return json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(v)
}

func (a *App) redirectURI() string { return "https://" + a.tailnet.Host() + "/callback" }

// ================= server pubblico (Funnel 443) =================

func (a *App) PublicHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /.well-known/appspecific/com.tesla.3p.public-key.pem", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-pem-file")
		w.Header().Set("Cache-Control", "public, max-age=300")
		_, _ = w.Write(a.tesla.PublicKeyPEM())
	})
	mux.HandleFunc("GET /callback", a.handleCallback)
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Vigilo</title><body style="font-family:system-ui;background:#000;color:#ddd;padding:24px"><h1>Vigilo</h1><p>Servizio personale attivo.</p></body>`)
	})
	api := a.apiMux()
	mux.Handle("/api/v1/", a.requirePairing(api))
	return securityHeaders(mux)
}

func securityHeaders(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		h.ServeHTTP(w, r)
	})
}

func (a *App) handleCallback(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	title, msg := "Accesso a Tesla completato", "Torna alla pagina di Vigilo in Home Assistant: il resto si configura da solo."
	if e := q.Get("error"); e != "" {
		title, msg = "Accesso annullato", e+" "+q.Get("error_description")
	} else {
		ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
		defer cancel()
		if err := a.tesla.ExchangeCode(ctx, q.Get("code"), q.Get("state"), a.redirectURI()); err != nil {
			title, msg = "Accesso non riuscito", err.Error()
		} else {
			a.events.Add(EvSetup, "Accesso a Tesla eseguito", "")
			go func() {
				c, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
				defer cancel()
				a.Setup(c)
			}()
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.WriteString(w, `<!doctype html><html lang="it"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Vigilo</title>
<body style="font-family:system-ui;background:#000;color:#eee;padding:28px;text-align:center"><h2>`+html.EscapeString(title)+`</h2><p style="color:#aaa">`+html.EscapeString(msg)+`</p><p style="color:#666">Puoi chiudere questa scheda.</p></body></html>`)
}

func (a *App) requirePairing(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		want := a.store.Get().PairingToken
		if want == "" || subtle.ConstantTimeCompare([]byte(tok), []byte(want)) != 1 {
			time.Sleep(300 * time.Millisecond)
			writeJSON(w, 401, map[string]any{"ok": false, "error": "app non associata: inquadra di nuovo il QR code"})
			return
		}
		a.store.NotePairedApp()
		h.ServeHTTP(w, r)
	})
}

// ================= API per l'app (pubblica, protetta dal token di associazione) =================

type statusResponse struct {
	OK           bool         `json:"ok"`
	Version      string       `json:"version"`
	VIN          string       `json:"vin"`
	Name         string       `json:"name"`
	Vehicle      VehicleState `json:"vehicle"`
	TelemetryExp int64        `json:"telemetry_exp"`
	KeyPaired    *bool        `json:"key_paired"`
}

func (a *App) status() statusResponse {
	s := a.store.Get()
	return statusResponse{OK: true, Version: a.version, VIN: s.VIN, Name: s.VehicleName, Vehicle: s.Vehicle,
		TelemetryExp: s.TelemetryExp, KeyPaired: s.KeyPaired}
}

func (a *App) apiMux() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/status", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, a.status()) })
	mux.HandleFunc("POST /api/v1/refresh", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
		defer cancel()
		if err := a.Refresh(ctx); err != nil {
			errJSON(w, 502, err)
			return
		}
		writeJSON(w, 200, a.status())
	})
	mux.HandleFunc("POST /api/v1/command", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Command string `json:"command"`
		}
		if err := readJSON(r, &body); err != nil {
			errJSON(w, 400, err)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 100*time.Second)
		defer cancel()
		msg, err := a.RunCommand(ctx, body.Command, "app Vigilo")
		if err != nil {
			errJSON(w, 502, err)
			return
		}
		st := a.status()
		writeJSON(w, 200, map[string]any{"ok": true, "message": msg, "status": st})
	})
	mux.HandleFunc("GET /api/v1/events", func(w http.ResponseWriter, r *http.Request) {
		after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit <= 0 || limit > 500 {
			limit = 200
		}
		writeJSON(w, 200, map[string]any{"ok": true, "events": a.events.List(after, limit)})
	})
	mux.HandleFunc("GET /api/v1/settings", func(w http.ResponseWriter, r *http.Request) {
		a.writeSettings(w, r.Context())
	})
	mux.HandleFunc("PUT /api/v1/settings", func(w http.ResponseWriter, r *http.Request) {
		var in Settings
		if err := readJSON(r, &in); err != nil {
			errJSON(w, 400, err)
			return
		}
		a.saveSettings(in)
		a.writeSettings(w, r.Context())
	})
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		writeJSON(w, 200, map[string]any{"ok": true, "checks": a.Checks(ctx, r.URL.Query().Get("deep") == "1")})
	})
	return mux
}

func (a *App) saveSettings(in Settings) {
	if in.AwareThrottleSec < 0 {
		in.AwareThrottleSec = 0
	}
	if in.AwareThrottleSec > 3600 {
		in.AwareThrottleSec = 3600
	}
	var clean []string
	for _, t := range in.NotifyTargets {
		t = strings.TrimPrefix(strings.TrimSpace(t), "notify.")
		if t != "" && !strings.ContainsAny(t, "/?# ") {
			clean = append(clean, t)
		}
	}
	in.NotifyTargets = clean
	_ = a.store.Update(func(s *State) { s.Settings = in })
}

func (a *App) writeSettings(w http.ResponseWriter, ctx context.Context) {
	c, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	avail, err := a.ha.NotifyServices(c)
	out := map[string]any{"ok": true, "settings": a.store.Get().Settings, "available_targets": avail}
	if err != nil {
		out["targets_error"] = err.Error()
	}
	writeJSON(w, 200, out)
}

// ================= interfaccia Home Assistant (Ingress) =================

// IngressHandler accetta solo il proxy Ingress di Home Assistant (172.30.32.2), che verifica già il login.
func (a *App) IngressHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		b, _ := webFS.ReadFile("web/index.html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(b)
	})
	mux.HandleFunc("GET /ui/state", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		s := a.store.Get()
		ts := a.tailnet.Status()
		host := ts.Host
		pairURL := ""
		if host != "" {
			pairURL = pairingLink(host, s.PairingToken)
		}
		a.mu.Lock()
		setupMsg := a.lastSetupMsg
		a.mu.Unlock()
		writeJSON(w, 200, map[string]any{
			"version":   a.version,
			"tailscale": ts,
			"host":      host,
			"origin": func() string {
				if host == "" {
					return ""
				}
				return "https://" + host
			}(),
			"redirect": func() string {
				if host == "" {
					return ""
				}
				return a.redirectURI()
			}(),
			"pair_key_url": func() string {
				if host == "" {
					return ""
				}
				return "https://tesla.com/_ak/" + host
			}(),
			"client_id":     s.ClientID,
			"has_secret":    s.ClientSecret != "",
			"region":        s.Region,
			"logged_in":     s.RefreshToken != "",
			"vin":           s.VIN,
			"vehicle_name":  s.VehicleName,
			"partner":       s.PartnerDomain,
			"key_paired":    s.KeyPaired,
			"telemetry_exp": s.TelemetryExp,
			"pair_url":      pairURL,
			"paired_apps":   s.PairedApps,
			"settings":      s.Settings,
			"setup_message": setupMsg,
			"checks":        a.Checks(ctx, r.URL.Query().Get("deep") == "1"),
			"vehicle":       s.Vehicle,
			"pubkey_fp":     a.tesla.PublicKeyFingerprint(),
		})
	})
	mux.HandleFunc("POST /ui/credentials", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			ClientID     string `json:"client_id"`
			ClientSecret string `json:"client_secret"`
			Region       string `json:"region"`
		}
		if err := readJSON(r, &in); err != nil {
			errJSON(w, 400, err)
			return
		}
		in.ClientID = strings.TrimSpace(in.ClientID)
		in.ClientSecret = strings.TrimSpace(in.ClientSecret)
		if in.Region != "eu" && in.Region != "na" {
			in.Region = regionForCountry(a.ha.Country(r.Context()))
		}
		_ = a.store.Update(func(s *State) {
			if in.ClientID != "" && in.ClientID != s.ClientID {
				s.ClientID = in.ClientID
				s.PartnerDomain = "" // nuova app Tesla: va registrato di nuovo il dominio
			}
			if in.ClientSecret != "" {
				s.ClientSecret = in.ClientSecret
			}
			s.Region = in.Region
		})
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /ui/login", func(w http.ResponseWriter, r *http.Request) {
		if a.tailnet.Host() == "" {
			errJSON(w, 409, errString("collega prima Tailscale"))
			return
		}
		u, err := a.tesla.AuthorizeURL(a.redirectURI())
		if err != nil {
			errJSON(w, 400, err)
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "url": u})
	})
	mux.HandleFunc("POST /ui/logout", func(w http.ResponseWriter, r *http.Request) {
		_ = a.store.Update(func(s *State) { s.AccessToken, s.RefreshToken, s.AccessExp = "", "", 0 })
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /ui/vehicles", func(w http.ResponseWriter, r *http.Request) {
		vs, err := a.tesla.Vehicles(r.Context())
		if err != nil {
			errJSON(w, 502, err)
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "vehicles": vs})
	})
	mux.HandleFunc("POST /ui/vehicle", func(w http.ResponseWriter, r *http.Request) {
		var in VehicleInfo
		if err := readJSON(r, &in); err != nil || len(in.VIN) != 17 {
			errJSON(w, 400, errString("VIN non valido"))
			return
		}
		_ = a.store.Update(func(s *State) { s.VIN, s.VehicleName = in.VIN, in.Name })
		go a.bus.Announce()
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /ui/run-setup", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
		defer cancel()
		_ = a.store.Update(func(s *State) { s.PartnerDomain = "" })
		a.Setup(ctx)
		a.mu.Lock()
		m := a.lastSetupMsg
		a.mu.Unlock()
		writeJSON(w, 200, map[string]any{"ok": true, "message": m})
	})
	mux.HandleFunc("POST /ui/telemetry", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
		defer cancel()
		msg, err := a.EnsureTelemetryConfig(ctx, true)
		if err != nil {
			errJSON(w, 502, err)
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "message": msg})
	})
	mux.HandleFunc("POST /ui/test-notify", func(w http.ResponseWriter, r *http.Request) {
		s := a.store.Get()
		if len(s.Settings.NotifyTargets) == 0 {
			errJSON(w, 400, errString("scegli almeno un telefono"))
			return
		}
		a.notifyAll(r.Context(), s.Settings.NotifyTargets, "Vigilo", "Notifica di prova: funziona!", map[string]any{"channel": "Sentinella"})
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /ui/settings", func(w http.ResponseWriter, r *http.Request) { a.writeSettings(w, r.Context()) })
	mux.HandleFunc("POST /ui/settings", func(w http.ResponseWriter, r *http.Request) {
		var in Settings
		if err := readJSON(r, &in); err != nil {
			errJSON(w, 400, err)
			return
		}
		a.saveSettings(in)
		a.writeSettings(w, r.Context())
	})
	mux.HandleFunc("POST /ui/pairing/reset", func(w http.ResponseWriter, r *http.Request) {
		a.store.ResetPairing()
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /ui/qr.png", func(w http.ResponseWriter, r *http.Request) {
		text := r.URL.Query().Get("t")
		switch text {
		case "pair":
			text = pairingLink(a.tailnet.Host(), a.store.Get().PairingToken)
		case "key":
			text = "https://tesla.com/_ak/" + a.tailnet.Host()
		default:
			http.Error(w, "qr sconosciuto", 400)
			return
		}
		png, err := qrcode.Encode(text, qrcode.Medium, 512)
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(png)
	})
	mux.HandleFunc("POST /ui/tailscale/login", func(w http.ResponseWriter, r *http.Request) {
		if err := a.tailnet.StartLogin(r.Context()); err != nil {
			errJSON(w, 500, err)
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /ui/events", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true, "events": a.events.List(0, 50)})
	})
	// L'interfaccia può usare anche i comandi.
	mux.Handle("/api/v1/", a.apiMux())
	return onlyIngress(securityHeaders(mux))
}

func onlyIngress(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		if host != "172.30.32.2" && host != "127.0.0.1" && host != "::1" {
			log.Printf("ingress: richiesta rifiutata da %s", host)
			http.Error(w, "solo tramite Home Assistant", http.StatusForbidden)
			return
		}
		h.ServeHTTP(w, r)
	})
}

func pairingLink(host, token string) string {
	return "vigilo://pair?url=" + url.QueryEscape("https://"+host) + "&token=" + url.QueryEscape(token)
}

type errString string

func (e errString) Error() string { return string(e) }
