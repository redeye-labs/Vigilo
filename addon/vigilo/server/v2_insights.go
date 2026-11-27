package main

import (
 "math"
 "time"
)

type V2ChargingSession struct {
 VehicleID VehicleID `json:"vehicle_id"`
 FirstSeen time.Time `json:"first_seen"`
 LastSeen time.Time `json:"last_seen"`
 BatteryEnergyKWh float64 `json:"battery_energy_kwh"`
 EnergySource string `json:"energy_source"`
 Coverage string `json:"coverage"`
 EstimatedCostEUR *float64 `json:"estimated_cost_eur,omitempty"`
 PriceSource string `json:"price_source,omitempty"`
}

// ChargingSessions derives observed charging sessions from Tesla's session
// counter DCChargingEnergyIn (usable for AC and DC per Fleet docs).
// A reset or a long data gap splits the observation. These are observations,
// not invoices, and missing observations never become guessed kWh.
func ChargingSessions(vehicle VehicleID, measurements []V2Measurement, tariff *float64) []V2ChargingSession {
 const maxGap=8*time.Hour
 result:=make([]V2ChargingSession,0)
 var current *V2ChargingSession
 var prev V2Measurement
 havePrev:=false
 commit:=func(){
  if current!=nil && current.BatteryEnergyKWh>0 {
   if tariff!=nil && !math.IsNaN(*tariff)&&!math.IsInf(*tariff,0)&&*tariff>=0{
    amount:=math.Round(current.BatteryEnergyKWh * (*tariff)*100)/100
    current.EstimatedCostEUR=&amount
    current.PriceSource="user_tariff_estimate_excludes_charger_losses"
   }
   result=append(result,*current)
  }
 }
 for _,m:=range measurements {
  if m.VehicleID!=vehicle || m.Signal!="DCChargingEnergyIn" || m.Value<0 || !finiteNumber(m.Value){continue}
  if havePrev && !m.ReceivedAt.After(prev.ReceivedAt){continue}
  split:=!havePrev || m.Value+0.05<prev.Value || m.ReceivedAt.Sub(prev.ReceivedAt)>maxGap
  if split{
   commit()
   current=&V2ChargingSession{
    VehicleID:vehicle,FirstSeen:m.ReceivedAt,LastSeen:m.ReceivedAt,
    BatteryEnergyKWh:m.Value,EnergySource:"tesla.DCChargingEnergyIn",
    Coverage:"observed_session_counter; may_be_partial",
   }
  }else if current!=nil{
   current.LastSeen=m.ReceivedAt
   if m.Value>current.BatteryEnergyKWh{current.BatteryEnergyKWh=m.Value}
  }
  havePrev=true
  prev=m
 }
 commit()
 return result
}

type V2TripObservation struct {
 VehicleID VehicleID `json:"vehicle_id"`
 FirstSeen time.Time `json:"first_seen"`
 LastSeen time.Time `json:"last_seen"`
 DistanceKm float64 `json:"distance_km"`
 Method string `json:"method"`
 Coverage string `json:"coverage"`
}

// TripObservations do NOT claim a complete trip log without GPS, gear state,
// and continuous telemetry. Distance comes from changes in the real odometer,
// whose Fleet Telemetry unit is miles. All times are approximate observation times.
func TripObservations(vehicle VehicleID, measurements []V2Measurement) []V2TripObservation {
 const maxGap=30*time.Minute
 results:=make([]V2TripObservation,0)
 var segment *V2TripObservation
 var prev V2Measurement
 havePrev:=false
 flush:=func(){
  if segment!=nil && segment.DistanceKm>=0.1 {
   segment.DistanceKm=math.Round(segment.DistanceKm*100)/100
   results=append(results,*segment)
  }
  segment=nil
 }
 for _,m:=range measurements{
  if m.VehicleID!=vehicle||m.Signal!="Odometer"||m.Value<0||!finiteNumber(m.Value){continue}
  if !havePrev{prev=m;havePrev=true;continue}
  gap:=m.ReceivedAt.Sub(prev.ReceivedAt)
  distance:=(m.Value-prev.Value)*1.609344
  if gap<=0 || gap>maxGap || distance<0 || distance>200 {
   flush()
   prev=m
   continue
  }
  if distance>0.01 {
   if segment==nil {
    segment=&V2TripObservation{
     VehicleID:vehicle,FirstSeen:prev.ReceivedAt,
     Method:"odometer_miles_delta * 1.609344",
     Coverage:"observed_odometer_movements_not_a_complete_trip_log",
    }
   }
   segment.LastSeen=m.ReceivedAt
   segment.DistanceKm+=distance
  }else{
   flush()
  }
  prev=m
 }
 flush()
 return results
}

func finiteNumber(v float64) bool{return !math.IsNaN(v)&&!math.IsInf(v,0)}

type V2BatteryObservations struct {
 NominalFullPackKWh *float64 `json:"nominal_full_pack_kwh,omitempty"`
 EnergyRemainingKWh *float64 `json:"energy_remaining_kwh,omitempty"`
 RatedRangeKm *float64 `json:"rated_range_km,omitempty"`
 ChargePercent *float64 `json:"charge_percent,omitempty"`
 HealthPercent *float64 `json:"health_percent,omitempty"`
 TempMinC *float64 `json:"temp_min_c,omitempty"`
 TempMaxC *float64 `json:"temp_max_c,omitempty"`
 EquivalentChargeCycles *float64 `json:"equivalent_charge_cycles,omitempty"`
 RatedFullRangeKm *float64 `json:"rated_full_range_km,omitempty"`
 LatestReceivedAt time.Time `json:"latest_received_at,omitempty"`
 Stale bool `json:"stale"`
 Note string `json:"note"`
}

func BatteryObservations(vehicle VehicleID,latest []V2Measurement) V2BatteryObservations {
 out:=V2BatteryObservations{Note:"Valori forniti dalla telemetria Tesla. SoH non disponibile senza una misura iniziale BMS validata."}
 var charged *float64
 var socTime, rangeTime time.Time
 allStale:=true
 for _,m:=range latest{
  if m.VehicleID!=vehicle||!finiteNumber(m.Value)||(m.Value<0 && m.Signal!="ModuleTempMin" && m.Signal!="ModuleTempMax"){continue}
  if m.ReceivedAt.After(out.LatestReceivedAt){out.LatestReceivedAt=m.ReceivedAt}
  if m.Quality!=QualityStale{allStale=false}
  value:=m.Value
  switch m.Signal{
  case "NominalFullPackEnergyKwh":out.NominalFullPackKWh=&value
  case "EnergyRemaining":out.EnergyRemainingKWh=&value
  case "ModuleTempMin":if value>=-60 && value<=100{out.TempMinC=&value}
  case "ModuleTempMax":if value>=-60 && value<=100{out.TempMaxC=&value}
  case "LifetimeEnergyChargedKwh":charged=&value
  case "RatedRange":
   rangeTime=m.ReceivedAt
   // Older Vigilo builds mislabeled raw Fleet Telemetry miles as km.
   if m.Source==SourceFleetTelemetry && (m.Unit=="mile" || m.Unit=="km"){value*=1.609344}
   out.RatedRangeKm=&value
  case "Soc":
   if value<=100{out.ChargePercent=&value;socTime=m.ReceivedAt}
  }
 }
 if charged!=nil && out.NominalFullPackKWh!=nil && *out.NominalFullPackKWh>0 {
  cycles:=*charged / *out.NominalFullPackKWh
  out.EquivalentChargeCycles=&cycles
 }
 if out.RatedRangeKm!=nil && out.ChargePercent!=nil && *out.ChargePercent>=10 && math.Abs(socTime.Sub(rangeTime).Minutes())<=5 {
  full:=*out.RatedRangeKm * 100 / *out.ChargePercent
  out.RatedFullRangeKm=&full
 }
 out.Stale=allStale
 if allStale && !out.LatestReceivedAt.IsZero(){
  out.Note="Ultime letture Tesla non recenti: valori storici, non aggiornati in tempo reale. SoH non disponibile senza riferimento BMS validato."
 }
 return out
}
