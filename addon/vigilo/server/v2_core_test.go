package main

import (
    "testing"
    "time"
)

func TestV2ModulesDisabledByDefault(t *testing.T) {
    var prefs V2ModulePreferences
    for _, m := range allV2Modules {
        if prefs.IsEnabled(m) { t.Fatalf("%s must be disabled by default", m) }
    }
    if err := prefs.Set(ModuleDiagnostics, true); err != nil { t.Fatal(err) }
    if !prefs.IsEnabled(ModuleDiagnostics) || prefs.IsEnabled(ModuleCommands) {
        t.Fatal("opt-in module state leaked to other module")
    }
    if err := prefs.Set("unknown", true); err == nil { t.Fatal("unknown module allowed") }
}

func TestV2MeasurementsRequireSourceAndCalculationMethod(t *testing.T) {
    now := time.Now()
    valid := V2Measurement{
        VehicleID: "vehicle-1", Signal: "RatedRange", Value: 278, Unit: "km",
        Source: SourceFleetTelemetry, Quality: QualityValid, MeasuredAt: now, ReceivedAt: now,
    }
    if err := valid.Validate(); err != nil { t.Fatal(err) }
    valid.Source = SourceCalculated
    if err := valid.Validate(); err == nil { t.Fatal("calculated reading without method was accepted") }
    valid.Method = "range_km / state_of_charge * 100"
    valid.Quality = QualityEstimated
    if err := valid.Validate(); err != nil { t.Fatal(err) }
    valid.Source = SourceFleetAPI
    if err := valid.Validate(); err == nil { t.Fatal("raw API value with invented method was accepted") }
}

func TestV2SecurityGroupingNeverMixesVehicles(t *testing.T) {
    origin := time.Date(2026, 11, 26, 20, 0, 0, 0, time.UTC)
    events := []V2SecurityEvent{
        {ID: 3, VehicleID: "a", Type: EvPanic, Time: origin.Add(3*time.Minute)},
        {ID: 1, VehicleID: "a", Type: EvAware, Time: origin},
        {ID: 2, VehicleID: "b", Type: EvAware, Time: origin.Add(time.Minute)},
        {ID: 4, VehicleID: "a", Type: EvAware, Time: origin.Add(20*time.Minute)},
    }
    incidents := GroupV2SecurityEvents(events, 5*time.Minute)
    if len(incidents) != 3 { t.Fatalf("wanted 3 incidents, got %+v", incidents) }
    if incidents[0].VehicleID != "a" || len(incidents[0].EventIDs) != 2 || !incidents[0].HasPanic {
        t.Fatalf("first incident not grouped correctly: %+v", incidents[0])
    }
    if incidents[1].VehicleID != "b" || len(incidents[1].EventIDs) != 1 { t.Fatal("vehicle events mixed") }
    if incidents[2].VehicleID != "a" || len(incidents[2].EventIDs) != 1 { t.Fatal("separate incident merged") }
    if len(events) != 4 || events[0].ID != 3 { t.Fatal("input was modified") }
}

func TestV2SecurityGroupingRejectsNonSentryEvents(t *testing.T) {
    now := time.Now()
    incidents := GroupV2SecurityEvents([]V2SecurityEvent{
        {ID: 1, VehicleID: "a", Type: EvCommand, Time: now},
        {ID: 2, VehicleID: "a", Type: EvAware, Time: now},
    }, 0)
    if len(incidents) != 1 || len(incidents[0].EventIDs) != 1 { t.Fatal("unexpected incident count") }
}
