package main

import "testing"

func TestUpgradeDoesNotReconfigureStableFleetTelemetry(t *testing.T) {
 baseline:=V2TelemetryFieldsHash(telemetryFields)
 current:=State{TelemetryFieldsVersion:2}
 if v2TelemetryFieldsRequireUpdate(current,baseline) {
  t.Fatal("ordinary 1.x to V2 upgrade must not touch Tesla telemetry")
 }
 current.TelemetryFieldsVersion=1
 if !v2TelemetryFieldsRequireUpdate(current,baseline) {
  t.Fatal("legacy missing Sentry configuration must still be repaired")
 }
 current.TelemetryFieldsVersion=2
 if !v2TelemetryFieldsRequireUpdate(current,"opted-in-additional-fields") {
  t.Fatal("opting in to V2 readings must update Tesla field list")
 }
 current.TelemetryFieldsVersion=3
 current.TelemetryConfigHash="opted-in-additional-fields"
 if v2TelemetryFieldsRequireUpdate(current,"opted-in-additional-fields") {
  t.Fatal("no change in selected V2 modules: no Tesla update")
 }
 if !v2TelemetryFieldsRequireUpdate(current,baseline) {
  t.Fatal("turning off extra fields must restore baseline")
 }
 current.TelemetryConfigHash=baseline
 if v2TelemetryFieldsRequireUpdate(current,baseline) {
  t.Fatal("matching V2 baseline must not be resent")
 }
}
