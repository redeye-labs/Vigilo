package main

import (
 "context"
 "crypto/subtle"
 "io"
 "encoding/json"
 "errors"
 "net/http"
 "strconv"
 "strings"
 "time"
)

// V2PublicAPI serves authenticated endpoints. Commands are opt-in and confirmed. This handler is
// mounted alongside the existing v1 API without changing old routes.
type V2PublicAPI struct {
 state *Store
 events *EventLog
 history *V2Archive
 modules *V2ModuleStore
 tesla *Tesla
 identity *V2VehicleIdentity
 metrics *V2Metrics
 rules *V2RuleEngine
}

func (v *V2PublicAPI) ServeHTTP(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("X-Content-Type-Options","nosniff")
 token:=v.state.Get().PairingToken
 supplied:=strings.TrimPrefix(r.Header.Get("Authorization"),"Bearer ")
 if token=="" || supplied=="" || subtle.ConstantTimeCompare([]byte(supplied),[]byte(token))!=1 {
  http.Error(w,"unauthorized",http.StatusUnauthorized)
  return
 }
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 s:=v.state.Get()
 vehicleID:=v.identity.ID(s.VIN)
 if r.Method==http.MethodPost && r.URL.Path=="/api/v2/commands" {
  v.command(w,r,s,vehicleID)
  return
 }
 if r.Method==http.MethodPut && r.URL.Path=="/api/v2/modules" {
  v.configureModule(w,r,vehicleID)
  return
 }
 if r.Method==http.MethodPut && r.URL.Path=="/api/v2/charging/tariff" {
  v.configureTariff(w,r,vehicleID)
  return
 }
 if r.Method==http.MethodPut && r.URL.Path=="/api/v2/automations" {
  v.configureAutomation(w,r,vehicleID)
  return
 }
 if r.Method==http.MethodDelete && r.URL.Path=="/api/v2/automations" {
  v.deleteAutomation(w,r,vehicleID)
  return
 }
 if r.Method!=http.MethodGet {
  w.Header().Set("Allow","GET")
  http.Error(w,"method not allowed",http.StatusMethodNotAllowed)
  return
 }
 switch r.URL.Path {
 case "/api/v2/capabilities":
  list:=make([]map[string]any,0,len(allV2Modules))
  for _,m:=range allV2Modules{
   list=append(list,map[string]any{
    "id":m,"enabled":v.modules.Enabled(vehicleID,m),
    // Only capabilities truly implemented may be marked available.
    "available":m==ModuleSecurity||m==ModuleDiagnostics||m==ModuleCommands||m==ModuleBattery||m==ModuleCharging||m==ModuleTrips||m==ModuleBackup||m==ModuleAutomation,
   })
  }
  v.write(w,map[string]any{"ok":true,"schema":1,"modules":list,"vehicle_configured":vehicleID!=""})
 case "/api/v2/battery/latest", "/api/v2/charging/latest", "/api/v2/trips/latest":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if v.metrics==nil{http.Error(w,"measurement archive unavailable",http.StatusServiceUnavailable);return}
  var module V2Module
  var signals []string
  switch r.URL.Path {
  case "/api/v2/battery/latest":
   module=ModuleBattery
   signals=[]string{"Soc","RatedRange","NominalFullPackEnergyKwh","EnergyRemaining"}
  case "/api/v2/charging/latest":
   module=ModuleCharging
   signals=[]string{"LifetimeEnergyChargedKwh","DCChargingEnergyIn","ACChargingEnergyIn","DCChargingPower"}
  case "/api/v2/trips/latest":
   module=ModuleTrips
   signals=[]string{"LifetimeEnergyUsed","Odometer"}
  }
  if !v.modules.Enabled(vehicleID,module){
   http.Error(w,"module disabled",http.StatusForbidden);return
  }
  samples:=v.metrics.Latest(vehicleID,signals)
  v.write(w,map[string]any{"ok":true,"vehicle_id":vehicleID,"samples":samples,"count":len(samples)})
 case "/api/v2/battery/trend":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if !v.modules.Enabled(vehicleID,ModuleBattery){http.Error(w,"module disabled",http.StatusForbidden);return}
  readings:=v.metrics.History(vehicleID,"NominalFullPackEnergyKwh",time.Now().AddDate(-2,0,0),20000)
  trend,available:=BatteryCapacityTrend(vehicleID,readings)
  if !available{
   v.write(w,map[string]any{"ok":true,"available":false,
    "note":"Occorrono almeno sei misure BMS coerenti distribuite su 30 giorni; il SoH resta non determinabile."})
   return
  }
  v.write(w,map[string]any{"ok":true,"available":true,"trend":trend})
 case "/api/v2/battery/summary":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if !v.modules.Enabled(vehicleID,ModuleBattery){http.Error(w,"module disabled",http.StatusForbidden);return}
  latest:=v.metrics.Latest(vehicleID,[]string{"Soc","RatedRange","EnergyRemaining","NominalFullPackEnergyKwh","ModuleTempMin","ModuleTempMax","LifetimeEnergyChargedKwh"})
  v.write(w,map[string]any{"ok":true,"battery":BatteryObservations(vehicleID,latest)})
 case "/api/v2/charging/sessions":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if !v.modules.Enabled(vehicleID,ModuleCharging){http.Error(w,"module disabled",http.StatusForbidden);return}
  since:=time.Now().AddDate(0,0,-90)
  observations:=v.metrics.History(vehicleID,"DCChargingEnergyIn",since,20000)
  tariff:=v.modules.Tariff(vehicleID)
  v.write(w,map[string]any{"ok":true,"sessions":ChargingSessions(vehicleID,observations,tariff),
   "coverage":"observed_samples_only; energy_received_by_battery_excludes_charger_losses"})
 case "/api/v2/charging/tariff":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  v.write(w,map[string]any{"ok":true,"price_eur_per_kwh":v.modules.Tariff(vehicleID),
    "source":"user_entered"})
 case "/api/v2/trips/energy":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if !v.modules.Enabled(vehicleID,ModuleTrips){http.Error(w,"module disabled",http.StatusForbidden);return}
  if v.metrics==nil{http.Error(w,"measurement archive unavailable",http.StatusServiceUnavailable);return}
  days:=30
  if n,e:=strconv.Atoi(r.URL.Query().Get("days"));e==nil&&n>=1&&n<=90{days=n}
  since:=time.Now().AddDate(0,0,-days)
  report:=ObservedEnergy(vehicleID,v.metrics.History(vehicleID,"LifetimeEnergyUsed",since,20000),v.metrics.History(vehicleID,"Odometer",since,20000),v.metrics.History(vehicleID,"Gear",since,20000),v.metrics.History(vehicleID,"SentryMode",since,20000))
  v.write(w,map[string]any{"ok":true,"report":report})
 case "/api/v2/trips/observations":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if !v.modules.Enabled(vehicleID,ModuleTrips){http.Error(w,"module disabled",http.StatusForbidden);return}
  since:=time.Now().AddDate(0,0,-90)
  observations:=v.metrics.History(vehicleID,"Odometer",since,20000)
  v.write(w,map[string]any{"ok":true,"observations":TripObservations(vehicleID,observations),
   "coverage":"odometer_movements_only_no_route_or_exact_trip_boundaries"})
 case "/api/v2/diagnostics/tires":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if !v.modules.Enabled(vehicleID,ModuleDiagnostics){http.Error(w,"module disabled",http.StatusForbidden);return}
  latest:=v.metrics.Latest(vehicleID,[]string{"TpmsPressureFl","TpmsPressureFr","TpmsPressureRl","TpmsPressureRr"})
  v.write(w,map[string]any{"ok":true,"readings":latest,"unit":"bar","coverage":"latest_reported_tire_pressures"})
 case "/api/v2/security/incidents":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  // Import is idempotent; legacy event files are never modified.
  _,err:=v.history.ImportLegacyEvents(vehicleID,v.events.List(0,1000))
  if err!=nil{http.Error(w,"history unavailable",http.StatusServiceUnavailable);return}
  limit:=200
  if q:=r.URL.Query().Get("limit");q!=""{
   if n,e:=strconv.Atoi(q);e==nil&&n>0&&n<=1000{limit=n}
  }
  groups:=v.history.SecurityIncidents(vehicleID,5*time.Minute,limit)
  v.write(w,map[string]any{"ok":true,"vehicle_id":vehicleID,"incidents":groups,"scope":"legacy events available on this server"})
 case "/api/v2/security/report":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if _,err:=v.history.ImportLegacyEvents(vehicleID,v.events.List(0,1000));err!=nil{
   http.Error(w,"history unavailable",http.StatusServiceUnavailable);return
  }
  days:=7
  if n,err:=strconv.Atoi(r.URL.Query().Get("days"));err==nil && n>=1 && n<=90{days=n}
  now:=time.Now().UTC()
  summary,err:=v.history.SecurityReport(vehicleID,now.AddDate(0,0,-days),now)
  if err!=nil{http.Error(w,"report unavailable",http.StatusServiceUnavailable);return}
  v.write(w,map[string]any{"ok":true,"report":summary})
 case "/api/v2/security/export.csv":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if _,err:=v.history.ImportLegacyEvents(vehicleID,v.events.List(0,1000));err!=nil{
   http.Error(w,"history unavailable",http.StatusServiceUnavailable);return
  }
  w.Header().Set("Content-Type","text/csv; charset=utf-8")
  w.Header().Set("Content-Disposition","attachment; filename=\"vigilo-security.csv\"")
  if err:=v.history.ExportSecurityCSV(w,vehicleID);err!=nil{return}
 case "/api/v2/automations":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if v.rules==nil{http.Error(w,"automation unavailable",http.StatusServiceUnavailable);return}
  v.write(w,map[string]any{"ok":true,"enabled":v.modules.Enabled(vehicleID,ModuleAutomation),"rules":v.rules.Rules(vehicleID)})
 case "/api/v2/backup/export":
  if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
  if !v.modules.Enabled(vehicleID,ModuleBackup){http.Error(w,"backup module disabled",http.StatusForbidden);return}
  if _,err:=v.history.ImportLegacyEvents(vehicleID,v.events.List(0,1000));err!=nil{
   http.Error(w,"history unavailable",http.StatusServiceUnavailable);return
  }
  snapshot,err:=CreateV2PortableExport(vehicleID,v.history,v.metrics,v.modules)
  if err!=nil{http.Error(w,"export unavailable",http.StatusServiceUnavailable);return}
  w.Header().Set("Content-Disposition","attachment; filename=\"vigilo2-data.json\"")
  v.write(w,snapshot)
 case "/api/v2/diagnostics":
  last:=time.Time{}
  if s.Vehicle.LastTelemetry>0{last=time.UnixMilli(s.Vehicle.LastTelemetry)}
  // Permission status cannot be inferred from a sleeping vehicle; avoid
  // presenting an unsupported 'permission OK' conclusion.
  sleeping:=strings.EqualFold(s.Vehicle.ConnState,"asleep")
  status:=DiagnoseConnection(true,true,sleeping,last,time.Now(),10*time.Minute)
  v.write(w,map[string]any{
   "ok":true,"vehicle_id":vehicleID,"telemetry_config_version":s.TelemetryFieldsVersion,
   "telemetry":status,"permission_status":"not_verified",
   "server_reachable":true,
  })
 default:
  http.NotFound(w,r)
 }
}


func (v *V2PublicAPI) configureModule(w http.ResponseWriter,r *http.Request,vehicleID VehicleID){
 if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
 var req struct{ Module V2Module `json:"module"`; Enabled bool `json:"enabled"` }
 if err:=json.NewDecoder(io.LimitReader(r.Body,2048)).Decode(&req);err!=nil{
  http.Error(w,"invalid JSON",http.StatusBadRequest);return
 }
 if !ValidV2Module(req.Module){http.Error(w,"unknown module",http.StatusBadRequest);return}
 if err:=v.modules.Set(vehicleID,req.Module,req.Enabled);err!=nil{
  http.Error(w,"module configuration failed",http.StatusServiceUnavailable);return
 }
 v.write(w,map[string]any{"ok":true,"module":req.Module,"enabled":req.Enabled})
}

func (v *V2PublicAPI) configureAutomation(w http.ResponseWriter,r *http.Request,vehicle VehicleID){
 if vehicle==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
 if v.rules==nil{http.Error(w,"automation unavailable",http.StatusServiceUnavailable);return}
 var rule V2AlertRule
 if err:=json.NewDecoder(io.LimitReader(r.Body,4096)).Decode(&rule);err!=nil{
  http.Error(w,"invalid rule",http.StatusBadRequest);return
 }
 // Never trust caller-supplied vehicle identity, timestamps or latch state.
 rule.VehicleID=vehicle
 rule.LastFiredAt=time.Time{}
 rule.Armed=false
 if err:=v.rules.Put(vehicle,rule);err!=nil{http.Error(w,err.Error(),http.StatusBadRequest);return}
 v.write(w,map[string]any{"ok":true,"id":rule.ID})
}

func (v *V2PublicAPI) deleteAutomation(w http.ResponseWriter,r *http.Request,vehicle VehicleID){
 if vehicle==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
 if v.rules==nil{http.Error(w,"automation unavailable",http.StatusServiceUnavailable);return}
 id:=r.URL.Query().Get("id")
 if id==""{http.Error(w,"rule id required",http.StatusBadRequest);return}
 if err:=v.rules.Delete(vehicle,id);err!=nil{http.Error(w,"delete failed",http.StatusServiceUnavailable);return}
 v.write(w,map[string]any{"ok":true})
}

func (v *V2PublicAPI) configureTariff(w http.ResponseWriter,r *http.Request,vehicle VehicleID){
 if vehicle==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
 if !v.modules.Enabled(vehicle,ModuleCharging){http.Error(w,"charging module disabled",http.StatusForbidden);return}
 var req struct {Price *float64 `json:"price_eur_per_kwh"`}
 if err:=json.NewDecoder(io.LimitReader(r.Body,1024)).Decode(&req);err!=nil{
  http.Error(w,"invalid JSON",http.StatusBadRequest);return
 }
 if err:=v.modules.SetTariff(vehicle,req.Price);err!=nil{
  http.Error(w,"invalid tariff",http.StatusBadRequest);return
 }
 v.write(w,map[string]any{"ok":true,"price_eur_per_kwh":req.Price})
}

// Verify a recent real vehicle state before accepting ANY advanced action.
// A cached "Online" flag alone is not a safety guarantee.
func validateV2CommandVehicle(s State, now time.Time) error {
 if !s.Vehicle.Online || !strings.EqualFold(s.Vehicle.ConnState,"online") {
  return errors.New("vehicle offline or sleeping")
 }
 if s.Vehicle.Gear!="P" {
  return errors.New("vehicle must report parked gear P")
 }
 last:=s.Vehicle.LastTelemetry
 if last<=0 || now.Sub(time.UnixMilli(last))>2*time.Minute ||
    time.UnixMilli(last).After(now.Add(30*time.Second)) {
  return errors.New("vehicle telemetry is not recent")
 }
 return nil
}

func (v *V2PublicAPI) command(w http.ResponseWriter,r *http.Request,s State,vehicleID VehicleID){
 if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
 if !v.modules.Enabled(vehicleID,ModuleCommands){
  http.Error(w,"advanced commands module disabled",http.StatusForbidden);return
 }
 if r.Header.Get("X-Vigilo-Confirm")!="yes"{
  http.Error(w,"explicit confirmation required",http.StatusBadRequest);return
 }
 var req struct{Command string `json:"command"`; Value *float64 `json:"value,omitempty"`}
 if err:=json.NewDecoder(io.LimitReader(r.Body,2048)).Decode(&req);err!=nil{
  http.Error(w,"invalid JSON",http.StatusBadRequest);return
 }
 if req.Value==nil{
  var err error
  _,err=ValidateV2AdvancedCommand(req.Command,true)
  if err!=nil{http.Error(w,"unknown command",http.StatusBadRequest);return}
 }else{
  if _,_,_,err:=ValidateV2ParameterCommand(req.Command,*req.Value,true);err!=nil{
   http.Error(w,"invalid command value",http.StatusBadRequest);return
  }
 }
 if err:=validateV2CommandVehicle(s,time.Now().UTC());err!=nil{
  // Never wake the car automatically to execute an advanced command.
  w.WriteHeader(http.StatusConflict)
  v.write(w,map[string]any{"ok":false,"error":err.Error()+"; no automatic wake"});return
 }
 if v.tesla==nil{http.Error(w,"commands unavailable",http.StatusServiceUnavailable);return}
 ctx,cancel:=context.WithTimeout(r.Context(),30*time.Second)
 defer cancel()
 var result string
 var err error
 if req.Value==nil{
  result,err=v.tesla.AdvancedCommand(ctx,s.VIN,req.Command,true)
 }else{
  result,err=v.tesla.AdvancedParameterCommand(ctx,s.VIN,req.Command,*req.Value,true)
 }
 if err!=nil{
  http.Error(w,"Tesla command not completed; check vehicle status",http.StatusBadGateway)
  return
 }
 v.events.Add(EvCommand,result,"Vigilo 2 manual")
 v.write(w,map[string]any{"ok":true,"message":result})
}

func (v *V2PublicAPI) write(w http.ResponseWriter,payload any) {
 if err:=json.NewEncoder(w).Encode(payload);err!=nil {
  // The response might already be committed; never log the payload.
  return
 }
}
