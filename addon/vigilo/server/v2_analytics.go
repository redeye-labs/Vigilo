package main

import (
 "errors"
 "math"
 "time"
)

// V2EnergyReport never fabricates a BMS estimate when Tesla does not provide one.
// All estimates are explicitly marked with their provenance and method.
type V2BatteryHealth struct {
 MeasuredFullPackKWh *float64 `json:"measured_full_pack_kwh,omitempty"`
 BaselineFullPackKWh *float64 `json:"baseline_full_pack_kwh,omitempty"`
 HealthPercent *float64 `json:"health_percent,omitempty"`
 DegradationPercent *float64 `json:"degradation_percent,omitempty"`
 Method string `json:"method,omitempty"`
 Note string `json:"note"`
}

// BatteryHealthEstimate compares two compatible BMS estimates for the same pack,
// not rated range or a nominal marketing capacity. Returns unavailable otherwise.
func BatteryHealthEstimate(currentBMSKWh, baselineBMSKWh *float64) V2BatteryHealth {
 r := V2BatteryHealth{Note:"Stima basata su letture BMS; non equivale a un test certificato."}
 if currentBMSKWh == nil || !finitePositive(*currentBMSKWh) {
  r.Note = "Capacità BMS attuale non disponibile."
  return r
 }
 c := *currentBMSKWh
 r.MeasuredFullPackKWh = &c
 if baselineBMSKWh == nil || !finitePositive(*baselineBMSKWh) {
  r.Note = "Manca una misura BMS iniziale confrontabile: SoH non calcolabile."
  return r
 }
 b := *baselineBMSKWh
 r.BaselineFullPackKWh = &b
 health := c / b * 100
 if health < 0 || health > 110 {
  r.Note = "Letture BMS incoerenti: SoH sospeso."
  return r
 }
 degradation := 100 - health
 r.HealthPercent = &health
 r.DegradationPercent = &degradation
 r.Method = "100 * nominal_full_pack_energy_kwh_current / nominal_full_pack_energy_kwh_baseline"
 return r
}

func finitePositive(x float64) bool { return x > 0 && !math.IsNaN(x) && !math.IsInf(x, 0) }

type V2ChargeCost struct {
 EnergyKWh float64 `json:"energy_kwh"`
 RateEuroPerKWh float64 `json:"rate_eur_per_kwh"`
 EstimatedCostEuro float64 `json:"estimated_cost_eur"`
 Method string `json:"method"`
}

// EstimateChargeCost uses a user-supplied tariff: NOT a provider invoice.
// Energy may be metered or from Tesla; its provenance must be supplied by the caller.
func EstimateChargeCost(energyKWh, rateEuroPerKWh float64) (V2ChargeCost, error) {
 if math.IsNaN(energyKWh) || math.IsInf(energyKWh, 0) || energyKWh < 0 ||
    math.IsNaN(rateEuroPerKWh) || math.IsInf(rateEuroPerKWh, 0) || rateEuroPerKWh < 0 {
  return V2ChargeCost{}, errors.New("valori di energia o tariffa non validi")
 }
 return V2ChargeCost{
  EnergyKWh:energyKWh, RateEuroPerKWh:rateEuroPerKWh,
  EstimatedCostEuro:math.Round(energyKWh*rateEuroPerKWh*100)/100,
  Method:"energy_kwh * user_supplied_eur_per_kwh; estimated, excludes fixed charges and losses",
 }, nil
}

type V2TripEfficiency struct {
 DistanceKm float64 `json:"distance_km"`
 EnergyKWh float64 `json:"energy_kwh"`
 KWhPer100Km float64 `json:"kwh_per_100km"`
 WhPerKm float64 `json:"wh_per_km"`
}

// CalculateTripEfficiency requires energy and distance measured for the SAME trip.
// SOC drop alone is insufficient without an actual usable-capacity measurement.
func CalculateTripEfficiency(distanceKm, energyKWh float64) (V2TripEfficiency, error) {
 if !finitePositive(distanceKm) || math.IsNaN(energyKWh) || math.IsInf(energyKWh,0) || energyKWh < 0 {
  return V2TripEfficiency{}, errors.New("viaggio privo di distanza o energia misurate valide")
 }
 return V2TripEfficiency{
  DistanceKm:distanceKm, EnergyKWh:energyKWh,
  KWhPer100Km:energyKWh*100/distanceKm, WhPerKm:energyKWh*1000/distanceKm,
 },nil
}

type V2DiagnosticStatus string

const (
 DiagnosticOK V2DiagnosticStatus = "ok"
 DiagnosticSleeping V2DiagnosticStatus = "vehicle_asleep"
 DiagnosticStale V2DiagnosticStatus = "telemetry_stale"
 DiagnosticServerOffline V2DiagnosticStatus = "server_offline"
 DiagnosticMissingPermission V2DiagnosticStatus = "missing_permission"
 DiagnosticNoData V2DiagnosticStatus = "no_data"
)

type V2Diagnostic struct {
 Status V2DiagnosticStatus `json:"status"`
 Details string `json:"details"`
 LastSeenAt time.Time `json:"last_seen_at,omitempty"`
}

// DiagnoseConnection distinguishes the user's phone/server connectivity,
// vehicle sleep, missing Tesla permissions and stale telemetry.
func DiagnoseConnection(serverReachable, permissionGranted, vehicleSleeping bool, lastSeen, now time.Time, maximumAge time.Duration) V2Diagnostic {
 if !serverReachable { return V2Diagnostic{Status:DiagnosticServerOffline, Details:"Server Vigilo non raggiungibile."} }
 if !permissionGranted { return V2Diagnostic{Status:DiagnosticMissingPermission, Details:"Autorizzazione Tesla insufficiente."} }
 if vehicleSleeping { return V2Diagnostic{Status:DiagnosticSleeping, Details:"La Tesla è addormentata; non è un guasto.", LastSeenAt:lastSeen} }
 if lastSeen.IsZero() { return V2Diagnostic{Status:DiagnosticNoData, Details:"Nessuna telemetria ricevuta."} }
 if maximumAge <= 0 { maximumAge = 10*time.Minute }
 if now.Sub(lastSeen) > maximumAge {
  return V2Diagnostic{Status:DiagnosticStale, Details:"Telemetria non aggiornata.", LastSeenAt:lastSeen}
 }
 return V2Diagnostic{Status:DiagnosticOK, Details:"Ultima telemetria recente.", LastSeenAt:lastSeen}
}
