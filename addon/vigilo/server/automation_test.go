package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func rome(t *testing.T) *time.Location {
	l, err := time.LoadLocation("Europe/Rome")
	if err != nil {
		t.Fatal(err)
	}
	return l
}

func TestShouldReact(t *testing.T) {
	x := AutoActions{Enabled: true, OnAware: true, Flash: true, CooldownSec: 300, TZ: "Europe/Rome"}
	now := time.Date(2026, 11, 17, 23, 0, 0, 0, rome(t))
	if !shouldReact(x, "Aware", now, time.Time{}) {
		t.Fatal("deve reagire ad Aware")
	}
	if shouldReact(x, "Panic", now, time.Time{}) {
		t.Fatal("Panic non scelto")
	}
	if shouldReact(x, "Aware", now, now.Add(-time.Minute)) {
		t.Fatal("pausa minima non rispettata")
	}
	if !shouldReact(x, "Aware", now, now.Add(-6*time.Minute)) {
		t.Fatal("dopo la pausa deve reagire")
	}
	off := x
	off.Enabled = false
	if shouldReact(off, "Aware", now, time.Time{}) {
		t.Fatal("disattivate")
	}
	none := x
	none.Flash = false
	if shouldReact(none, "Aware", now, time.Time{}) {
		t.Fatal("nessuna azione scelta")
	}
	h := x
	h.HoursOnly, h.FromMin, h.ToMin = true, 22*60, 7*60
	if !shouldReact(h, "Aware", now, time.Time{}) {
		t.Fatal("23:00 è nella fascia 22-7")
	}
	if shouldReact(h, "Aware", time.Date(2026, 11, 17, 12, 0, 0, 0, rome(t)), time.Time{}) {
		t.Fatal("12:00 è fuori dalla fascia 22-7")
	}
	if !shouldReact(h, "Aware", time.Date(2026, 11, 17, 6, 59, 0, 0, rome(t)), time.Time{}) {
		t.Fatal("6:59 è nella fascia 22-7")
	}
}

func TestDueRules(t *testing.T) {
	s := Schedules{TZ: "Europe/Rome", Rules: []ScheduleRule{
		{ID: 1, Hour: 22, Minute: 30, DaysMask: 1 << 1, Enable: true, Active: true}, // martedì
		{ID: 2, Hour: 22, Minute: 30, DaysMask: 1 << 0, Enable: false, Active: true}, // lunedì
		{ID: 3, Hour: 22, Minute: 30, DaysMask: 0x7f, Enable: false, Active: false},  // disattiva
	}}
	tue := time.Date(2026, 11, 17, 22, 33, 0, 0, rome(t)) // martedì
	d := dueRules(s, tue.UTC())
	if len(d) != 1 || d[0].Rule.ID != 1 {
		t.Fatalf("attese solo regola 1: %+v", d)
	}
	if len(dueRules(s, tue.Add(-4*time.Minute))) != 0 {
		t.Fatal("prima dell'orario non deve partire")
	}
	if len(dueRules(s, tue.Add(8*time.Minute))) != 0 {
		t.Fatal("oltre 10 minuti non deve partire")
	}
}

func TestSchedulesNormalized(t *testing.T) {
	out, err := Schedules{TZ: "Europe/Rome", Rules: []ScheduleRule{
		{Hour: 23, Minute: 0, DaysMask: 0xff, Active: true},
		{ID: 5, Hour: 7, Minute: 0, DaysMask: 1, Active: true},
	}}.normalized()
	if err != nil {
		t.Fatal(err)
	}
	if out.Rules[0].ID != 5 || out.Rules[1].ID != 6 || out.Rules[1].DaysMask != 0x7f {
		t.Fatalf("normalizzazione: %+v", out.Rules)
	}
	if _, err := (Schedules{Rules: []ScheduleRule{{Hour: 24}}}).normalized(); err == nil {
		t.Fatal("ora 24 non valida")
	}
	if _, err := (Schedules{TZ: "Marte/Base"}).normalized(); err == nil {
		t.Fatal("fuso non valido")
	}
}

func TestActionsAndSchedulesAPI(t *testing.T) {
	dir := t.TempDir()
	st, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	a := &App{store: st, ha: &HA{}}
	h := a.apiMux()
	do := func(method, path, body string) map[string]any {
		t.Helper()
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(method, path, strings.NewReader(body)))
		if rec.Code != 200 {
			t.Fatalf("%s %s: %d %s", method, path, rec.Code, rec.Body.String())
		}
		var m map[string]any
		_ = json.Unmarshal(rec.Body.Bytes(), &m)
		return m
	}
	act := do("GET", "/api/v1/actions", "")["actions"].(map[string]any)
	if act["enabled"] != false || act["cooldown_sec"].(float64) != 300 {
		t.Fatalf("predefinite: %v", act)
	}
	do("PUT", "/api/v1/actions", `{"enabled":true,"on_panic":true,"honk":true,"cooldown_sec":5}`)
	got := st.Get().AutoActions
	if !got.Enabled || !got.Honk || got.CooldownSec != 30 {
		t.Fatalf("salvate: %+v", got)
	}
	sch := do("GET", "/api/v1/schedules", "")["schedules"].(map[string]any)
	if rules, ok := sch["rules"].([]any); !ok || len(rules) != 0 {
		t.Fatalf("lista vuota attesa: %v", sch)
	}
	do("PUT", "/api/v1/schedules", `{"tz":"Europe/Rome","rules":[{"hour":22,"minute":0,"days_mask":127,"enable":true,"active":true}]}`)
	if r := st.Get().Schedules.Rules; len(r) != 1 || r[0].ID != 1 {
		t.Fatalf("regole: %+v", r)
	}
	// le vecchie app salvano solo settings: azioni e programmazione restano
	do("PUT", "/api/v1/settings", `{"notify_aware":true}`)
	if !st.Get().AutoActions.Enabled || len(st.Get().Schedules.Rules) != 1 {
		t.Fatal("PUT settings non deve toccare azioni e programmazione")
	}
}

func TestOldStateKeepsDefaults(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte(`{"vin":"X","settings":{"notify_aware":true}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if x := st.Get().AutoActions; x.Enabled || x.CooldownSec != 300 || !x.Flash {
		t.Fatalf("predefinite dopo l'aggiornamento: %+v", x)
	}
}
