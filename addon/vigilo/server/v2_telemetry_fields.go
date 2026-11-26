package main

import (
 "crypto/sha256"
 "encoding/hex"
 "encoding/json"
)

// V2TelemetryFields only requests additional fields when their module is enabled.
// Existing Sentry monitoring fields are always retained. Unsupported readings
// are simply absent; no synthetic values or extra polling are used.
func V2TelemetryFields(prefs *V2ModuleStore, vehicle VehicleID) map[string]any {
 fields:=make(map[string]any,len(telemetryFields)+16)
 for name,cfg:=range telemetryFields{fields[name]=cfg}
 if prefs==nil || vehicle==""{return fields}
 add:=func(seconds int,names ...string){
  for _,name:=range names{fields[name]=map[string]any{"interval_seconds":seconds}}
 }
 if prefs.Enabled(vehicle,ModuleBattery){
  add(900,"NominalFullPackEnergyKwh","EnergyRemaining")
 }
 if prefs.Enabled(vehicle,ModuleCharging){
  add(300,"LifetimeEnergyChargedKwh","ACChargingEnergyIn","DCChargingEnergyIn","DCChargingPower")
 }
 if prefs.Enabled(vehicle,ModuleTrips){
  // LifetimeEnergyUsedDrive is Tesla Semi only. On Model 3, LifetimeEnergyUsed
  // also includes non-driving consumption; trips must label that limitation.
  add(300,"Odometer","LifetimeEnergyUsed")
 }
 if prefs.Enabled(vehicle,ModuleDiagnostics){
  add(900,"TpmsPressureFl","TpmsPressureFr","TpmsPressureRl","TpmsPressureRr")
 }
 return fields
}

func V2TelemetryFieldsHash(fields map[string]any) string{
 b,_:=json.Marshal(fields) // encoding/json sorts map keys.
 sum:=sha256.Sum256(b)
 return hex.EncodeToString(sum[:])
}
