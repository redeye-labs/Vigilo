package main

import (
 "context"
 "crypto/subtle"
 "io"
 "encoding/json"
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
    "available":m==ModuleSecurity||m==ModuleDiagnostics||m==ModuleCommands||m==ModuleBattery,
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
   signals=[]string{"Soc","RatedRange","NominalFullPackEnergyKwh"}
  case "/api/v2/charging/latest":
   module=ModuleCharging
   signals=[]string{"LifetimeEnergyChargedKwh"}
  case "/api/v2/trips/latest":
   module=ModuleTrips
   signals=[]string{"LifetimeEnergyUsedDrive","Odometer"}
  }
  if !v.modules.Enabled(vehicleID,module){
   http.Error(w,"module disabled",http.StatusForbidden);return
  }
  samples:=v.metrics.Latest(vehicleID,signals)
  v.write(w,map[string]any{"ok":true,"vehicle_id":vehicleID,"samples":samples,"count":len(samples)})
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

func (v *V2PublicAPI) command(w http.ResponseWriter,r *http.Request,s State,vehicleID VehicleID){
 if vehicleID==""{http.Error(w,"vehicle not configured",http.StatusConflict);return}
 if !v.modules.Enabled(vehicleID,ModuleCommands){
  http.Error(w,"advanced commands module disabled",http.StatusForbidden);return
 }
 if r.Header.Get("X-Vigilo-Confirm")!="yes"{
  http.Error(w,"explicit confirmation required",http.StatusBadRequest);return
 }
 var req struct{ Command string `json:"command"` }
 if err:=json.NewDecoder(io.LimitReader(r.Body,2048)).Decode(&req);err!=nil{
  http.Error(w,"invalid JSON",http.StatusBadRequest);return
 }
 spec,err:=ValidateV2AdvancedCommand(req.Command,true)
 if err!=nil{http.Error(w,"unknown command",http.StatusBadRequest);return}
 if !s.Vehicle.Online{
  http.Error(w,"vehicle offline or sleeping; no automatic wake",http.StatusConflict);return
 }
 if spec.Sensitive && s.Vehicle.Gear!="P"{
  http.Error(w,"vehicle must be parked to operate trunk",http.StatusConflict);return
 }
 if v.tesla==nil{http.Error(w,"commands unavailable",http.StatusServiceUnavailable);return}
 ctx,cancel:=context.WithTimeout(r.Context(),30*time.Second)
 defer cancel()
 result,err:=v.tesla.AdvancedCommand(ctx,s.VIN,req.Command,true)
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
