package main

import (
    "errors"
    "sort"
    "strings"
    "time"
)

// Future-facing Vigilo 2.0 domain types. Not yet connected to the existing
// HTTP endpoints, telemetry ingestion, or legacy state storage.
// Keeping them isolated preserves compatibility with existing installations.

type V2Module string

const (
    ModuleSecurity V2Module = "security"
    ModuleDiagnostics V2Module = "diagnostics"
    ModuleBackup V2Module = "backup"
    ModuleCommands V2Module = "commands"
    ModuleBattery V2Module = "battery"
    ModuleCharging V2Module = "charging"
    ModuleAutomation V2Module = "automation"
    ModuleTrips V2Module = "trips"
)

var allV2Modules = []V2Module{
    ModuleSecurity, ModuleDiagnostics, ModuleBackup, ModuleCommands,
    ModuleBattery, ModuleCharging, ModuleAutomation, ModuleTrips,
}

func ValidV2Module(m V2Module) bool {
    for _, known := range allV2Modules {
        if m == known {
            return true
        }
    }
    return false
}

// V2ModulePreferences is opt-in by default. The existing Sentry features
// are intentionally outside this map and remain enabled independently.
type V2ModulePreferences struct {
    Enabled map[V2Module]bool `json:"enabled"`
}

func (p V2ModulePreferences) IsEnabled(m V2Module) bool {
    return ValidV2Module(m) && p.Enabled[m]
}

func (p *V2ModulePreferences) Set(m V2Module, enabled bool) error {
    if !ValidV2Module(m) {
        return errors.New("modulo Vigilo sconosciuto")
    }
    if p.Enabled == nil {
        p.Enabled = make(map[V2Module]bool)
    }
    p.Enabled[m] = enabled
    return nil
}

// VehicleID is an internal, non-secret ID, not a VIN or a Tesla access token.
type VehicleID string

type V2Vehicle struct {
    ID VehicleID `json:"id"`
    VIN string `json:"vin,omitempty"`
    Name string `json:"name"`
    Active bool `json:"active"`
}

type MeasurementSource string

const (
    SourceFleetTelemetry MeasurementSource = "fleet_telemetry"
    SourceFleetAPI MeasurementSource = "fleet_api"
    SourceCalculated MeasurementSource = "calculated"
)

type MeasurementQuality string

const (
    QualityValid MeasurementQuality = "valid"
    QualityStale MeasurementQuality = "stale"
    QualityIncomplete MeasurementQuality = "incomplete"
    QualityEstimated MeasurementQuality = "estimated"
)

type V2Measurement struct {
    VehicleID VehicleID `json:"vehicle_id"`
    Signal string `json:"signal"`
    Value float64 `json:"value"`
    Unit string `json:"unit"`
    Source MeasurementSource `json:"source"`
    Quality MeasurementQuality `json:"quality"`
    MeasuredAt time.Time `json:"measured_at"`
    ReceivedAt time.Time `json:"received_at"`
    Method string `json:"method,omitempty"`
}

func (m V2Measurement) Validate() error {
    if m.VehicleID == "" || strings.TrimSpace(m.Signal) == "" || m.MeasuredAt.IsZero() || m.ReceivedAt.IsZero() {
        return errors.New("misura incompleta")
    }
    switch m.Source {
    case SourceFleetTelemetry, SourceFleetAPI:
        if m.Method != "" { return errors.New("metodo non previsto per un dato originale") }
    case SourceCalculated:
        if strings.TrimSpace(m.Method) == "" { return errors.New("formula obbligatoria per un dato calcolato") }
    default:
        return errors.New("sorgente sconosciuta")
    }
    switch m.Quality {
    case QualityValid, QualityStale, QualityIncomplete, QualityEstimated:
    default:
        return errors.New("qualità sconosciuta")
    }
    return nil
}

type V2SecurityEvent struct {
    ID int64 `json:"id"`
    VehicleID VehicleID `json:"vehicle_id"`
    Type string `json:"type"`
    Time time.Time `json:"time"`
}

type V2SecurityIncident struct {
    VehicleID VehicleID `json:"vehicle_id"`
    StartedAt time.Time `json:"started_at"`
    EndedAt time.Time `json:"ended_at"`
    EventIDs []int64 `json:"event_ids"`
    HasPanic bool `json:"has_panic"`
}

// GroupV2SecurityEvents groups nearby events without ever deleting or
// elevating the underlying Tesla event types. Different vehicles never mix.
func GroupV2SecurityEvents(input []V2SecurityEvent, window time.Duration) []V2SecurityIncident {
    if window <= 0 { window = 5 * time.Minute }
    events := append([]V2SecurityEvent(nil), input...)
    sort.SliceStable(events, func(i, j int) bool { return events[i].Time.Before(events[j].Time) })
    incidents := make([]V2SecurityIncident, 0)
    for _, e := range events {
        if e.VehicleID == "" || e.Time.IsZero() || (e.Type != EvAware && e.Type != EvPanic) { continue }
        match := -1
        for i := len(incidents)-1; i >= 0; i-- {
            inc := &incidents[i]
            if inc.VehicleID == e.VehicleID && !e.Time.After(inc.EndedAt.Add(window)) && !e.Time.Before(inc.EndedAt) {
                match = i
                break
            }
        }
        if match < 0 {
            incidents = append(incidents, V2SecurityIncident{
                VehicleID: e.VehicleID, StartedAt: e.Time, EndedAt: e.Time,
                EventIDs: []int64{e.ID}, HasPanic: e.Type == EvPanic,
            })
        } else {
            inc := &incidents[match]
            inc.EndedAt = e.Time
            inc.EventIDs = append(inc.EventIDs, e.ID)
            inc.HasPanic = inc.HasPanic || e.Type == EvPanic
        }
    }
    return incidents
}
