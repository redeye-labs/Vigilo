package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// HA parla con Home Assistant tramite il Supervisor (token SUPERVISOR_TOKEN fornito all'add-on).
type HA struct {
	mu     sync.Mutex
	token  string
	source string
	client *http.Client
}

func NewHA() *HA {
	h := &HA{client: &http.Client{Timeout: 15 * time.Second}}
	h.token, h.source = supervisorToken()
	return h
}

// supervisorEnvDirs: l'immagine base usa s6-overlay (/init), che di default azzera l'ambiente del
// processo principale e salva le variabili del container in questi file (come fa "with-contenv").
var supervisorEnvDirs = []string{"/run/s6/container_environment", "/var/run/s6/container_environment"}

// supervisorToken cerca il token del Supervisor nell'ambiente e, se manca, nei file di s6-overlay.
// Restituisce anche la fonte (mai il valore) per il registro.
func supervisorToken() (token, source string) {
	for _, name := range []string{"SUPERVISOR_TOKEN", "HASSIO_TOKEN"} {
		if v := strings.TrimSpace(os.Getenv(name)); v != "" {
			return v, "ambiente " + name
		}
	}
	for _, dir := range supervisorEnvDirs {
		for _, name := range []string{"SUPERVISOR_TOKEN", "HASSIO_TOKEN"} {
			if b, err := os.ReadFile(dir + "/" + name); err == nil {
				if v := strings.TrimSpace(string(b)); v != "" {
					return v, dir + "/" + name
				}
			}
		}
	}
	return "", ""
}

func (h *HA) Available() bool { return h.ensureToken() }

// ensureToken riprova a leggere il token se all'avvio non c'era.
func (h *HA) ensureToken() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.token == "" {
		h.token, h.source = supervisorToken()
	}
	return h.token != ""
}

// Source descrive da dove arriva il token (per il registro), o "" se manca.
func (h *HA) Source() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.source
}

func (h *HA) do(ctx context.Context, method, path string, body any, out any) error {
	if !h.ensureToken() {
		return errors.New("token del Supervisor mancante: controlla che Vigilo sia avviato come componente aggiuntivo di Home Assistant e riavvialo")
	}
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequestWithContext(ctx, method, "http://supervisor"+path, rd)
	req.Header.Set("Authorization", "Bearer "+h.token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := h.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode >= 300 {
		msg := strings.TrimSpace(string(b))
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return fmt.Errorf("Supervisor %s %s: HTTP %d %s", method, path, resp.StatusCode, msg)
	}
	if out != nil {
		return json.Unmarshal(b, out)
	}
	return nil
}

type MQTTService struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	SSL      bool   `json:"ssl"`
	Username string `json:"username"`
	Password string `json:"password"`
}

// MQTT restituisce le credenziali del broker (add-on Mosquitto) fornite dal Supervisor.
func (h *HA) MQTT(ctx context.Context) (*MQTTService, error) {
	var r struct {
		Data MQTTService `json:"data"`
	}
	if err := h.do(ctx, "GET", "/services/mqtt", nil, &r); err != nil {
		return nil, fmt.Errorf("broker MQTT non trovato tramite il Supervisor (%w); verifica che Mosquitto broker sia avviato, oppure compila mqtt_host/mqtt_username/mqtt_password nella Configurazione di Vigilo", err)
	}
	if r.Data.Host == "" {
		return nil, errors.New("broker MQTT non configurato: installa e avvia l'add-on Mosquitto broker")
	}
	return &r.Data, nil
}

// NotifyServices elenca i servizi notify.* (es. mobile_app_telefono).
func (h *HA) NotifyServices(ctx context.Context) ([]string, error) {
	var r []struct {
		Domain   string                     `json:"domain"`
		Services map[string]json.RawMessage `json:"services"`
	}
	if err := h.do(ctx, "GET", "/core/api/services", nil, &r); err != nil {
		return nil, err
	}
	var out []string
	for _, d := range r {
		if d.Domain != "notify" {
			continue
		}
		for name := range d.Services {
			if name == "notify" || name == "send_message" || name == "persistent_notification" {
				continue
			}
			out = append(out, name)
		}
	}
	// Prima i telefoni (mobile_app_*), poi gli altri servizi notify.
	sort.Slice(out, func(i, j int) bool {
		pi, pj := strings.HasPrefix(out[i], "mobile_app_"), strings.HasPrefix(out[j], "mobile_app_")
		if pi != pj {
			return pi
		}
		return out[i] < out[j]
	})
	return out, nil
}

// Country (es. "IT") dalla configurazione di Home Assistant, per scegliere la regione Tesla.
func (h *HA) Country(ctx context.Context) string {
	var r struct {
		Country string `json:"country"`
	}
	if h.do(ctx, "GET", "/core/api/config", nil, &r) != nil {
		return ""
	}
	return r.Country
}

func (h *HA) Notify(ctx context.Context, target, title, message string, data map[string]any) error {
	target = strings.TrimPrefix(target, "notify.")
	body := map[string]any{"title": title, "message": message}
	if data != nil {
		body["data"] = data
	}
	return h.do(ctx, "POST", "/core/api/services/notify/"+target, body, nil)
}

func (h *HA) PersistentNotification(ctx context.Context, id, title, message string) error {
	return h.do(ctx, "POST", "/core/api/services/persistent_notification/create", map[string]any{
		"notification_id": id, "title": title, "message": message,
	}, nil)
}

// FireEvent pubblica un evento (es. vigilo_sentry) utilizzabile nelle automazioni.
func (h *HA) FireEvent(ctx context.Context, name string, data map[string]any) error {
	return h.do(ctx, "POST", "/core/api/events/"+name, data, nil)
}

// regionForCountry: Nord America e Asia-Pacifico usano la regione "na", il resto "eu".
func regionForCountry(c string) string {
	switch strings.ToUpper(c) {
	case "US", "CA", "MX", "PR", "JP", "KR", "AU", "NZ", "CN", "HK", "MO", "TW", "SG", "TH", "MY", "PH":
		return "na"
	}
	return "eu"
}
