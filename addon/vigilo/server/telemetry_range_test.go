package main

import (
    "math"
    "testing"
)

func TestTelemetryRangeConvertsMilesToKilometers(t *testing.T) {
    var v VehicleState
    updateRangeFromTelemetry(&v, "EstBatteryRange", float64(243.6))
    if v.RangeKm == nil || math.Abs(*v.RangeKm-243.6*1.609344) > 0.01 {
        t.Fatalf("estimated range must be converted to km, got %v", v.RangeKm)
    }
}

func TestRatedRangePreferredOverEstimatedRange(t *testing.T) {
    var v VehicleState
    updateRangeFromTelemetry(&v, "EstBatteryRange", float64(243.6))
    updateRangeFromTelemetry(&v, "RatedRange", float64(278))
    updateRangeFromTelemetry(&v, "EstBatteryRange", float64(250))
    if v.RangeKm == nil || math.Abs(*v.RangeKm-278*1.609344)>0.01 || !v.RangeRated {
        t.Fatalf("expected Tesla rated 278 miles converted to km to take priority, got %+v", v)
    }
}

func TestRangeRejectsInvalidTelemetry(t *testing.T) {
    var v VehicleState
    updateRangeFromTelemetry(&v, "RatedRange", -25.0)
    updateRangeFromTelemetry(&v, "EstBatteryRange", "invalid")
    if v.RangeKm != nil || v.RangeRated {
        t.Fatalf("invalid telemetry must not set range, got %+v", v)
    }
}

func TestTelemetryConfigurationIncludesRatedRange(t *testing.T) {
    if _, ok := telemetryFields["RatedRange"]; !ok {
        t.Fatal("rated range missing from telemetry configuration")
    }
}
