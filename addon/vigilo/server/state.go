package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Settings modificabili dall'app e dalla procedura guidata.
type Settings struct {
	NotifyTargets    []string `json:"notify_targets"`     // es. ["mobile_app_telefono"] (senza "notify.")
	NotifyAware      bool     `json:"notify_aware"`       // "qualcuno vicino all'auto"
	NotifyPanic      bool     `json:"notify_panic"`       // allarme
	AwareThrottleSec int      `json:"aware_throttle_sec"` // pausa minima tra due avvisi Aware
	NotifyProblems   bool     `json:"notify_problems"`    // controllo giornaliero fallito
}

// VehicleState: ultimo stato noto, da telemetria (in tempo reale) o da lettura Fleet API.
type VehicleState struct {
	Sentry        string   `json:"sentry"` // Off, Idle, Armed, Aware, Panic, Quiet, ""
	SentryAt      int64    `json:"sentry_at"`
	Locked        *bool    `json:"locked"`
	DoorsOpen     *bool    `json:"doors_open"`
	WindowsOpen   *bool    `json:"windows_open"`
	Battery       *float64 `json:"battery"`
	RangeKm       *float64 `json:"range_km"`
	InsideTemp    *float64 `json:"inside_temp"`  // °C, da climate_state (stessa lettura, nessuna chiamata in più)
	OutsideTemp   *float64 `json:"outside_temp"` // °C
	OdometerKm    *float64 `json:"odometer_km"`
	TrunkOpen     *bool    `json:"trunk_open"`
	FrunkOpen     *bool    `json:"frunk_open"`
	Charging      string   `json:"charging"`
	Lat           *float64 `json:"lat"`
	Lon           *float64 `json:"lon"`
	Gear          string   `json:"gear"`
	Online        bool     `json:"online"`
	ConnState     string   `json:"connection_state"`
	Streaming     bool     `json:"telemetry_connected"`
	LastTelemetry int64    `json:"telemetry_last_seen"`
	UpdatedAt     int64    `json:"updated_at"`
}

// State persistente in /data/state.json (permessi 0600). Contiene segreti: non va mai loggato.
type State struct {
	ClientID            string       `json:"client_id"`
	ClientSecret        string       `json:"client_secret"`
	Region              string       `json:"region"` // "eu" | "na"
	AccessToken         string       `json:"access_token"`
	RefreshToken        string       `json:"refresh_token"`
	AccessExp           int64        `json:"access_exp"`
	FleetHost           string       `json:"fleet_host"`
	VIN                 string       `json:"vin"`
	VehicleName         string       `json:"vehicle_name"`
	PartnerDomain       string       `json:"partner_domain"`
	PairingToken        string       `json:"pairing_token"`
	PairedApps          int          `json:"paired_apps"` // app che hanno usato il token (almeno una chiamata API riuscita)
	LastAppAt           int64        `json:"last_app_at,omitempty"`
	TelemetryExp        int64        `json:"telemetry_exp"`
	TelemetryHost       string       `json:"telemetry_host"`
	TelemetrySent       int64        `json:"telemetry_sent"`
	KeyPaired           *bool        `json:"key_paired"`
	TelemetrySynced     *bool        `json:"telemetry_synced"`
	LastHealthOK        int64        `json:"last_health_ok"`
	LastProblemNotified int64        `json:"last_problem_notified"`
	Settings            Settings     `json:"settings"`
	AutoActions         AutoActions  `json:"auto_actions"`
	Schedules           Schedules    `json:"schedules"`
	Vehicle             VehicleState `json:"vehicle"`
	// Solo modalità standalone (nil, e quindi assente dal file, nel componente aggiuntivo).
	Ntfy *NtfyConfig `json:"ntfy,omitempty"`
}

type Store struct {
	mu   sync.Mutex
	path string
	s    State
}

func defaultSettings() Settings {
	return Settings{NotifyAware: true, NotifyPanic: true, AwareThrottleSec: 120, NotifyProblems: true}
}

func NewStore(dir string) (*Store, error) {
	st := &Store{path: filepath.Join(dir, "state.json")}
	st.s.Settings = defaultSettings()
	st.s.AutoActions = defaultAutoActions()
	if b, err := os.ReadFile(st.path); err == nil {
		if err := json.Unmarshal(b, &st.s); err != nil {
			return nil, err
		}
	}
	if st.s.PairingToken == "" {
		st.s.PairingToken = randomToken(32)
		_ = st.saveLocked()
	}
	if st.s.Settings.AwareThrottleSec == 0 {
		st.s.Settings.AwareThrottleSec = 120
	}
	return st, nil
}

// Get restituisce una copia dello stato.
func (st *Store) Get() State {
	st.mu.Lock()
	defer st.mu.Unlock()
	c := st.s
	c.Settings.NotifyTargets = append([]string(nil), st.s.Settings.NotifyTargets...)
	c.Schedules.Rules = append([]ScheduleRule(nil), st.s.Schedules.Rules...)
	if st.s.Ntfy != nil {
		n := *st.s.Ntfy
		c.Ntfy = &n
	}
	return c
}

// Update modifica lo stato e lo salva.
func (st *Store) Update(fn func(s *State)) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	fn(&st.s)
	return st.saveLocked()
}

func (st *Store) saveLocked() error {
	b, err := json.MarshalIndent(&st.s, "", "  ")
	if err != nil {
		return err
	}
	tmp := st.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, st.path)
}

func randomToken(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// NotePairedApp registra che un'app ha usato con successo il token di abbinamento.
// È idempotente e scrive su disco solo la prima volta, per non farlo a ogni polling.
func (st *Store) NotePairedApp() {
	st.mu.Lock()
	already := st.s.PairedApps > 0
	st.mu.Unlock()
	if already {
		return
	}
	_ = st.Update(func(s *State) {
		if s.PairedApps == 0 {
			s.PairedApps = 1
		}
		s.LastAppAt = time.Now().UnixMilli()
	})
}

// ResetPairing invalida il token e azzera le app collegate (pulsante «Rigenera»).
func (st *Store) ResetPairing() {
	_ = st.Update(func(s *State) {
		s.PairingToken = randomToken(32)
		s.PairedApps = 0
		s.LastAppAt = 0
	})
}

func nowUnix() int64 { return time.Now().Unix() }

// ---------- storico eventi ----------

type Event struct {
	ID      int64  `json:"id"`
	TS      int64  `json:"ts"` // unix millis
	Type    string `json:"type"`
	Title   string `json:"title"`
	Message string `json:"message"`
}

const (
	EvAware    = "SENTRY_AWARE"
	EvPanic    = "SENTRY_PANIC"
	EvSentry   = "SENTRY_CHANGE"
	EvCommand  = "COMMAND"
	EvConnect  = "TELEMETRY"
	EvProblem  = "ERROR"
	EvSetup    = "SETUP"
	EvSchedule = "SCHEDULE"
	maxEvents  = 1000
)

type EventLog struct {
	mu     sync.Mutex
	path   string
	events []Event
	next   int64
}

func NewEventLog(dir string) *EventLog {
	l := &EventLog{path: filepath.Join(dir, "events.json"), next: 1}
	if b, err := os.ReadFile(l.path); err == nil {
		_ = json.Unmarshal(b, &l.events)
		for _, e := range l.events {
			if e.ID >= l.next {
				l.next = e.ID + 1
			}
		}
	}
	return l
}

func (l *EventLog) Add(typ, title, msg string) Event {
	l.mu.Lock()
	e := Event{ID: l.next, TS: time.Now().UnixMilli(), Type: typ, Title: title, Message: msg}
	l.next++
	l.events = append(l.events, e)
	if len(l.events) > maxEvents {
		l.events = l.events[len(l.events)-maxEvents:]
	}
	b, _ := json.Marshal(l.events)
	_ = os.WriteFile(l.path, b, 0o600)
	l.mu.Unlock()
	return e
}

// List restituisce gli eventi con id > after, dal più recente, al massimo limit.
func (l *EventLog) List(after int64, limit int) []Event {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := []Event{}
	for i := len(l.events) - 1; i >= 0 && len(out) < limit; i-- {
		if l.events[i].ID > after {
			out = append(out, l.events[i])
		}
	}
	return out
}
