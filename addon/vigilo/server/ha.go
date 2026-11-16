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
	"time"
)

// HA parla con Home Assistant tramite il Supervisor (token SUPERVISOR_TOKEN fornito all'add-on).
type HA struct {
	token  string
	client *http.Client
}

func NewHA() *HA {
	return &HA{token: os.Getenv("SUPERVISOR_TOKEN"), client: &http.Client{Timeout: 15 * time.Second}}
}

func (h *HA) Available() bool { return h.token != "" }

func (h *HA) do(ctx context.Context, method, path string, body any, out any) error {
	if h.token == "" {
		return errors.New("Supervisor non disponibile (avvio fuori da Home Assistant)")
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
		return fmt.Errorf("Home Assistant %s %s: %d", method, path, resp.StatusCode)
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
		return nil, fmt.Errorf("broker MQTT non trovato (installa l'add-on Mosquitto broker): %w", err)
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
	sort.Strings(out)
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
