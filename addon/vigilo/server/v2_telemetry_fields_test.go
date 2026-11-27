package main

import (
	"testing"
)

func TestV2TelemetryRequestsOnlyOptedInFields(t *testing.T) {
	store, err := OpenV2ModuleStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	veh := VehicleID("car-1")
	baseline := V2TelemetryFields(store, veh)
	if _, ok := baseline["NominalFullPackEnergyKwh"]; ok {
		t.Fatal("BMS requested by default")
	}
	if _, ok := baseline["DCChargingEnergyIn"]; ok {
		t.Fatal("charging requested by default")
	}
	if _, ok := baseline["TpmsPressureFl"]; ok {
		t.Fatal("tires requested by default")
	}
	if _, ok := baseline["SentryMode"]; !ok {
		t.Fatal("legacy Sentry field missing")
	}
	first := V2TelemetryFieldsHash(baseline)
	if err = store.Set(veh, ModuleBattery, true); err != nil {
		t.Fatal(err)
	}
	upgraded := V2TelemetryFields(store, veh)
	if _, ok := upgraded["NominalFullPackEnergyKwh"]; !ok {
		t.Fatal("BMS not requested")
	}
	if _, ok := upgraded["DCChargingEnergyIn"]; ok {
		t.Fatal("charging leaked into battery module")
	}
	if V2TelemetryFieldsHash(upgraded) == first {
		t.Fatal("config fingerprint unchanged")
	}
	if V2TelemetryFieldsHash(upgraded) != V2TelemetryFieldsHash(V2TelemetryFields(store, veh)) {
		t.Fatal("non-deterministic telemetry fingerprint")
	}
	if _, ok := V2TelemetryFields(store, "car-2")["NominalFullPackEnergyKwh"]; ok {
		t.Fatal("module selection leaked across vehicles")
	}
	if err = store.Set(veh, ModuleBattery, false); err != nil {
		t.Fatal(err)
	}
	if hash := V2TelemetryFieldsHash(V2TelemetryFields(store, veh)); hash != first {
		t.Fatal("disable not restoring baseline")
	}
}

func TestV2TelemetryDoesNotRequestSemiOnlyEnergyField(t *testing.T) {
	store, err := OpenV2ModuleStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err = store.Set("car", ModuleTrips, true); err != nil {
		t.Fatal(err)
	}
	fields := V2TelemetryFields(store, "car")
	if _, ok := fields["LifetimeEnergyUsedDrive"]; ok {
		t.Fatal("Tesla Semi-only field requested for Model 3")
	}
	if _, ok := fields["LifetimeEnergyUsed"]; !ok {
		t.Fatal("Model 3 battery use counter missing")
	}
	if _, ok := fields["Odometer"]; !ok {
		t.Fatal("odometer missing")
	}
	for _, name := range []string{"Odometer", "LifetimeEnergyUsed", "Gear", "SentryMode"} {
		if fields[name].(map[string]any)["resend_interval_seconds"] != 300 {
			t.Errorf("%s does not resend while streaming", name)
		}
	}
	if _, ok := V2TelemetryFields(store, "other")["SentryMode"].(map[string]any)["resend_interval_seconds"]; ok {
		t.Fatal("trip sampling leaked into the default Sentinella settings")
	}
}

func TestHomeLocationOnlyWhenExplicitAwayRuleConfigured(t *testing.T) {
	store, err := OpenV2ModuleStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	vehicle := VehicleID("car")
	if err = store.Set(vehicle, ModuleAutomation, true); err != nil {
		t.Fatal(err)
	}
	if _, ok := V2TelemetryFields(store, vehicle)["LocatedAtHome"]; ok {
		t.Fatal("location data requested for unrelated automations")
	}
	if _, ok := V2TelemetryFields(store, vehicle, true)["LocatedAtHome"]; !ok {
		t.Fatal("home flag not requested for opted-in parked-away rule")
	}
	if err = store.Set(vehicle, ModuleAutomation, false); err != nil {
		t.Fatal(err)
	}
	if _, ok := V2TelemetryFields(store, vehicle, true)["LocatedAtHome"]; ok {
		t.Fatal("disabled module still requesting home data")
	}
}
