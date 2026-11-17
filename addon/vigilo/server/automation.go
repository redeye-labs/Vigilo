package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"sort"
	"strings"
	"sync"
	"time"
	_ "time/tzdata" // fusi orari anche senza tzdata nel sistema
)

// ================= azioni automatiche e programmazione Sentinella =================
//
// Stanno in campi separati dello stato (non in Settings), così le app che salvano
// /api/v1/settings senza conoscerli non li cancellano.

// AutoActions: cosa fa l'auto quando la Sentinella rileva qualcuno (Aware) o scatta l'allarme (Panic).
type AutoActions struct {
	Enabled     bool   `json:"enabled"`
	OnAware     bool   `json:"on_aware"`
	OnPanic     bool   `json:"on_panic"`
	Flash       bool   `json:"flash"`
	Honk        bool   `json:"honk"`
	CooldownSec int    `json:"cooldown_sec"` // pausa minima tra due reazioni
	HoursOnly   bool   `json:"hours_only"`   // solo nella fascia oraria qui sotto
	FromMin     int    `json:"from_min"`     // minuti dalla mezzanotte (fascia che può scavalcare la mezzanotte)
	ToMin       int    `json:"to_min"`
	TZ          string `json:"tz"` // fuso orario del telefono, es. Europe/Rome
}

func defaultAutoActions() AutoActions {
	return AutoActions{OnAware: true, Flash: true, CooldownSec: 300, FromMin: 22 * 60, ToMin: 7 * 60}
}

func (x AutoActions) normalized() AutoActions {
	if x.CooldownSec < 30 {
		x.CooldownSec = 30
	}
	if x.CooldownSec > 3600 {
		x.CooldownSec = 3600
	}
	x.FromMin = clampMin(x.FromMin)
	x.ToMin = clampMin(x.ToMin)
	x.TZ = strings.TrimSpace(x.TZ)
	return x
}

// ScheduleRule: accende o spegne la Sentinella a un'ora, nei giorni indicati (bit0 = lunedì … bit6 = domenica).
type ScheduleRule struct {
	ID       int64 `json:"id"`
	Hour     int   `json:"hour"`
	Minute   int   `json:"minute"`
	DaysMask int   `json:"days_mask"`
	Enable   bool  `json:"enable"`
	Active   bool  `json:"active"`
}

type Schedules struct {
	TZ    string         `json:"tz"`
	Rules []ScheduleRule `json:"rules"`
}

func clampMin(m int) int {
	if m < 0 {
		return 0
	}
	if m > 24*60-1 {
		return 24*60 - 1
	}
	return m
}

func (s Schedules) normalized() (Schedules, error) {
	out := Schedules{TZ: strings.TrimSpace(s.TZ)}
	if out.TZ != "" {
		if _, err := time.LoadLocation(out.TZ); err != nil {
			return out, fmt.Errorf("fuso orario non valido: %s", out.TZ)
		}
	}
	if len(s.Rules) > 50 {
		return out, errors.New("troppe regole (massimo 50)")
	}
	var next int64 = 1
	for _, r := range s.Rules {
		if r.ID >= next {
			next = r.ID + 1
		}
	}
	for _, r := range s.Rules {
		if r.Hour < 0 || r.Hour > 23 || r.Minute < 0 || r.Minute > 59 {
			return out, errors.New("orario non valido")
		}
		r.DaysMask &= 0x7f
		if r.ID <= 0 {
			r.ID = next
			next++
		}
		out.Rules = append(out.Rules, r)
	}
	sort.Slice(out.Rules, func(i, j int) bool {
		a, b := out.Rules[i], out.Rules[j]
		return a.Hour*60+a.Minute < b.Hour*60+b.Minute
	})
	return out, nil
}

func location(tz string) *time.Location {
	if tz != "" {
		if l, err := time.LoadLocation(tz); err == nil {
			return l
		}
	}
	return time.Local
}

// inWindow: true se il minuto m cade nella fascia [from, to), anche a cavallo della mezzanotte.
func inWindow(m, from, to int) bool {
	if from == to {
		return true
	}
	if from < to {
		return m >= from && m < to
	}
	return m >= from || m < to
}

// dayBit: bit del giorno della settimana (lunedì = bit0).
func dayBit(t time.Time) int {
	wd := int(t.Weekday()) // domenica = 0
	return 1 << ((wd + 6) % 7)
}

// ---------- azioni automatiche ----------

type automation struct {
	mu        sync.Mutex
	lastReact time.Time
	// programmazione: esito per regola nel giorno corrente (chiave id@data)
	runs map[string]*schedRun
}

type schedRun struct {
	done     bool
	attempts int
	last     time.Time
}

// shouldReact decide (senza effetti) se reagire a un evento della Sentinella.
func shouldReact(x AutoActions, state string, now, last time.Time) bool {
	if !x.Enabled || (!x.Flash && !x.Honk) {
		return false
	}
	if (state == "Aware" && !x.OnAware) || (state == "Panic" && !x.OnPanic) || (state != "Aware" && state != "Panic") {
		return false
	}
	if !last.IsZero() && now.Sub(last) < time.Duration(x.CooldownSec)*time.Second {
		return false
	}
	if x.HoursOnly {
		lt := now.In(location(x.TZ))
		if !inWindow(lt.Hour()*60+lt.Minute(), x.FromMin, x.ToMin) {
			return false
		}
	}
	return true
}

// autoReact viene chiamata a ogni nuovo stato Aware/Panic della Sentinella.
func (a *App) autoReact(state string) {
	x := a.store.Get().AutoActions.normalized()
	a.auto.mu.Lock()
	if !shouldReact(x, state, time.Now(), a.auto.lastReact) {
		a.auto.mu.Unlock()
		return
	}
	a.auto.lastReact = time.Now()
	a.auto.mu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		why := "qualcuno vicino all'auto"
		if state == "Panic" {
			why = "allarme"
		}
		if x.Flash {
			if _, err := a.runCommandAs(ctx, "flash", "azione automatica ("+why+")", EvCommand); err != nil {
				log.Printf("azione automatica fari: %v", err)
			}
		}
		if x.Honk {
			if _, err := a.runCommandAs(ctx, "honk", "azione automatica ("+why+")", EvCommand); err != nil {
				log.Printf("azione automatica clacson: %v", err)
			}
		}
	}()
}

// ---------- programmazione ----------

// dueRules restituisce le regole da eseguire adesso: dal minuto previsto fino a 10 minuti dopo
// (così un riavvio o un errore temporaneo non fanno perdere la regola).
func dueRules(s Schedules, now time.Time) []struct {
	Rule ScheduleRule
	Key  string
} {
	lt := now.In(location(s.TZ))
	var out []struct {
		Rule ScheduleRule
		Key  string
	}
	for _, r := range s.Rules {
		if !r.Active || r.DaysMask == 0 {
			continue
		}
		at := time.Date(lt.Year(), lt.Month(), lt.Day(), r.Hour, r.Minute, 0, 0, lt.Location())
		if dayBit(at)&r.DaysMask == 0 {
			continue
		}
		if d := lt.Sub(at); d >= 0 && d < 10*time.Minute {
			out = append(out, struct {
				Rule ScheduleRule
				Key  string
			}{r, fmt.Sprintf("%d@%s", r.ID, at.Format("2006-01-02T15:04"))})
		}
	}
	return out
}

// ScheduleLoop esegue la programmazione della Sentinella sul server, indipendente dal telefono.
func (a *App) ScheduleLoop(ctx context.Context) {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for {
		a.runSchedules(ctx, time.Now())
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (a *App) runSchedules(ctx context.Context, now time.Time) {
	s := a.store.Get()
	if s.VIN == "" {
		return
	}
	for _, d := range dueRules(s.Schedules, now) {
		a.auto.mu.Lock()
		if a.auto.runs == nil {
			a.auto.runs = map[string]*schedRun{}
		}
		r := a.auto.runs[d.Key]
		if r == nil {
			r = &schedRun{}
			a.auto.runs[d.Key] = r
		}
		skip := r.done || r.attempts >= 3 || (!r.last.IsZero() && now.Sub(r.last) < 2*time.Minute)
		if !skip {
			r.attempts++
			r.last = now
		}
		a.auto.mu.Unlock()
		if skip {
			continue
		}
		cmd := "sentry_off"
		if d.Rule.Enable {
			cmd = "sentry_on"
		}
		cctx, cancel := context.WithTimeout(ctx, 100*time.Second)
		_, err := a.runCommandAs(cctx, cmd, fmt.Sprintf("programmazione delle %02d:%02d", d.Rule.Hour, d.Rule.Minute), EvSchedule)
		cancel()
		a.auto.mu.Lock()
		if err == nil {
			r.done = true
		}
		// pulizia delle chiavi vecchie
		for k, v := range a.auto.runs {
			if now.Sub(v.last) > 24*time.Hour {
				delete(a.auto.runs, k)
			}
		}
		a.auto.mu.Unlock()
	}
}
