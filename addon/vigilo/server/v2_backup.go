package main

import (
 "crypto/sha256"
 "encoding/hex"
 "encoding/json"
 "errors"
 "time"
)

// V2PortableExport deliberately excludes VINs, OAuth tokens, TLS keys, pairing
// tokens, locations, arbitrary event messages and server configuration. It is
// a data export, not a full server disaster-recovery backup.
type V2PortableExport struct{
 Schema int `json:"schema"`
 CreatedAt time.Time `json:"created_at"`
 Scope string `json:"scope"`
 Events []V2ArchivedEvent `json:"events"`
 Measurements []V2Measurement `json:"measurements"`
 TariffEURPerKWh *float64 `json:"tariff_eur_per_kwh,omitempty"`
 Checksum string `json:"checksum_sha256"`
}

var allowedPortableFields=[]string{
 "Soc","RatedRange","EnergyRemaining","NominalFullPackEnergyKwh",
 "DCChargingEnergyIn","ACChargingEnergyIn","LifetimeEnergyChargedKwh","DCChargingPower",
 "Odometer","LifetimeEnergyUsed",
 "TpmsPressureFl","TpmsPressureFr","TpmsPressureRl","TpmsPressureRr",
}

func CreateV2PortableExport(vehicle VehicleID,history *V2Archive,metrics *V2Metrics,modules *V2ModuleStore)(V2PortableExport,error){
 if vehicle==""||history==nil||metrics==nil||modules==nil{
  return V2PortableExport{},errors.New("archivio Vigilo 2 non disponibile")
 }
 snapshot:=V2PortableExport{
  Schema:1,CreatedAt:time.Now().UTC(),
  Scope:"non_secret_vehicle_history_only; not_full_server_backup",
  Events:[]V2ArchivedEvent{},Measurements:[]V2Measurement{},
  TariffEURPerKWh:modules.Tariff(vehicle),
 }
 for _,e:=range history.Snapshot(vehicle,10000){
  // Export only known event kinds. Unknown types may be user-controlled.
  switch e.Type{
  case EvAware,EvPanic,EvSentry,EvCommand,EvConnect,EvProblem,EvSetup,EvSchedule:
  default:continue
  }
  // Keep event type and UTC timestamp, strip untrusted message/title.
  snapshot.Events=append(snapshot.Events,V2ArchivedEvent{
   ID:e.ID,Timestamp:e.Timestamp,Type:e.Type,
   // Use a generic label instead of a VIN-derived vehicle id.
   VehicleID:"export_vehicle",
  })
 }
 since:=time.Time{}
 for _,signal:=range allowedPortableFields{
  for _,m:=range metrics.History(vehicle,signal,since,20000){
   m.VehicleID="export_vehicle"
   snapshot.Measurements=append(snapshot.Measurements,m)
  }
 }
 snapshot.Checksum=V2PortableChecksum(snapshot)
 return snapshot,nil
}

func V2PortableChecksum(snapshot V2PortableExport) string{
 snapshot.Checksum=""
 b,_:=json.Marshal(snapshot)
 sum:=sha256.Sum256(b)
 return hex.EncodeToString(sum[:])
}

func VerifyV2PortableExport(snapshot V2PortableExport) error{
 if snapshot.Schema!=1||snapshot.Checksum==""||snapshot.Checksum!=V2PortableChecksum(snapshot){
  return errors.New("esportazione Vigilo 2 non valida o alterata")
 }
 if len(snapshot.Events)>10000||len(snapshot.Measurements)>20000{
  return errors.New("esportazione troppo grande")
 }
 for _,e:=range snapshot.Events{
  if e.VehicleID!="export_vehicle"||e.Title!=""||e.Message!=""||e.Timestamp<=0{return errors.New("evento non valido")}
  switch e.Type {
  case EvAware,EvPanic,EvSentry,EvCommand,EvConnect,EvProblem,EvSetup,EvSchedule:
  default:return errors.New("tipo evento non valido")
  }
 }
 allow:=map[string]bool{}
 for _,s:=range allowedPortableFields{allow[s]=true}
 for _,m:=range snapshot.Measurements{
  if m.VehicleID!="export_vehicle"||!allow[m.Signal]||!finiteNumber(m.Value)||m.Source!=SourceFleetTelemetry{
   return errors.New("misura non valida")
  }
 }
 return nil
}
