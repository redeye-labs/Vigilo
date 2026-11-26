package main

import (
 "crypto/subtle"
 "encoding/json"
 "net/http"
 "strconv"
 "strings"
 "time"
)

// V2PublicAPI serves read-only, token-protected endpoints. This handler is
// mounted alongside the existing v1 API without changing old routes.
type V2PublicAPI struct {
 state *Store
 events *EventLog
 history *V2Archive
 modules *V2ModuleStore
}

func (v *V2PublicAPI) ServeHTTP(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("X-Content-Type-Options","nosniff")
 if r.Method!=http.MethodGet {
  w.Header().Set("Allow","GET")
  http.Error(w,"method not allowed",http.StatusMethodNotAllowed)
  return
 }
 token:=v.state.Get().PairingToken
 supplied:=strings.TrimPrefix(r.Header.Get("Authorization"),"Bearer ")
 if token=="" || supplied=="" || subtle.ConstantTimeCompare([]byte(supplied),[]byte(token))!=1 {
  http.Error(w,"unauthorized",http.StatusUnauthorized)
  return
 }
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 s:=v.state.Get()
 vehicleID:=VehicleID(s.VIN)
 switch r.URL.Path {
 case "/api/v2/capabilities":
  list:=make([]map[string]any,0,len(allV2Modules))
  for _,m:=range allV2Modules{
   list=append(list,map[string]any{
    "id":m,"enabled":v.modules.Enabled(vehicleID,m),
    // Only capabilities truly implemented may be marked available.
    "available":m==ModuleSecurity||m==ModuleDiagnostics,
   })
  }
  v.write(w,map[string]any{"ok":true,"schema":1,"modules":list,"vehicle_configured":vehicleID!=""})
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

func (v *V2PublicAPI) write(w http.ResponseWriter,payload any) {
 if err:=json.NewEncoder(w).Encode(payload);err!=nil {
  // The response might already be committed; never log the payload.
  return
 }
}
