package main

import (
	"math"
	"testing"
	"time"
)

func TestObservedEnergySeparatesDrivingAndParked(t *testing.T) {
	now := time.Now()
	samples := func(signal string, values ...float64) []V2Measurement {
		out := []V2Measurement{}
		for i, v := range values {
			out = append(out, V2Measurement{VehicleID: "car", Signal: signal, Value: v, Quality: QualityValid, ReceivedAt: now.Add(time.Duration(i) * 5 * time.Minute)})
		}
		return out
	}
	r := ObservedEnergy("car", samples("LifetimeEnergyUsed", 100, 101, 102, 102.2), samples("Odometer", 10, 14, 14, 14), samples("Gear", 1, 1, 0, 0), samples("SentryMode", 0, 0, 1, 1))
	if r.DrivingKm == nil || math.Abs(*r.DrivingKm-6.437376) > 0.001 || r.WhPerKm == nil {
		t.Fatalf("driving %+v", r)
	}
	if r.ParkedKWh == nil || r.SentryKWh == nil || math.Abs(*r.ParkedKWh-0.2) > 0.001 || math.Abs(*r.SentryKWh-0.2) > 0.001 {
		t.Fatalf("parked %+v", r)
	}
	missing := ObservedEnergy("car", samples("LifetimeEnergyUsed", 100, 101), samples("Odometer", 10, 14), nil, nil)
	if missing.DrivingKWh != nil || missing.ParkedKWh != nil {
		t.Fatal("classified without evidence")
	}
}

func TestObservedParkingDoesNotBridgeMissingIntervals(t *testing.T) {
	start := time.Date(2026, 11, 27, 10, 0, 0, 0, time.UTC)
	minutes := []int{0, 5, 10, 45, 50, 55}
	samples := func(signal string, values ...float64) []V2Measurement {
		out := make([]V2Measurement, 0, len(values))
		for i, value := range values {
			out = append(out, V2Measurement{
				VehicleID: "car", Signal: signal, Value: value, Quality: QualityValid,
				ReceivedAt: start.Add(time.Duration(minutes[i]) * time.Minute),
			})
		}
		return out
	}

	report := ObservedEnergy("car",
		samples("LifetimeEnergyUsed", 10, 11, 12, 18, 19, 20),
		samples("Odometer", 30, 30, 30, 30, 30, 30),
		samples("Gear", 0, 0, 0, 0, 0, 0),
		samples("SentryMode", 0, 0, 0, 1, 1, 0),
	)
	if len(report.Parking) != 3 {
		t.Fatalf("parking = %+v", report.Parking)
	}
	for i, state := range []string{"off", "on", "unknown"} {
		if report.Parking[i].Sentry != state {
			t.Errorf("parking[%d] = %+v, want %s", i, report.Parking[i], state)
		}
	}
	if report.SentryOffKWh == nil || *report.SentryOffKWh != 2 ||
		report.SentryKWh == nil || *report.SentryKWh != 1 ||
		report.ParkedKWh == nil || *report.ParkedKWh != 4 {
		t.Fatalf("parking totals = %+v", report)
	}
}
