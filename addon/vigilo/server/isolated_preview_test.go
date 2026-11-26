package main

import (
 "encoding/json"
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
)

func TestIsolatedPreviewHealthHasNoVehicleCapabilities(t *testing.T) {
 req:=httptest.NewRequest(http.MethodGet,"/health",nil)
 w:=httptest.NewRecorder()
 isolatedPreviewHandler().ServeHTTP(w,req)
 if w.Code!=http.StatusOK{t.Fatalf("status=%d body=%s",w.Code,w.Body.String())}
 var data struct{
  OK bool `json:"ok"`
  Mode string `json:"mode"`
  TeslaCommandsEnabled bool `json:"tesla_commands_enabled"`
  MQTTEnabled bool `json:"mqtt_enabled"`
  TailscaleEnabled bool `json:"tailscale_enabled"`
  FleetTelemetryEnabled bool `json:"fleet_telemetry_enabled"`
  SmokeOnly bool `json:"smoke_test_only"`
 }
 if err:=json.Unmarshal(w.Body.Bytes(),&data);err!=nil{t.Fatal(err)}
 if !data.OK||data.Mode!="isolated_preview"||!data.SmokeOnly||
     data.TeslaCommandsEnabled||data.MQTTEnabled||data.TailscaleEnabled||data.FleetTelemetryEnabled{
  t.Fatalf("unsafe preview state %+v",data)
 }
}

func TestIsolatedPreviewRejectsAPIsAndChanges(t *testing.T){
 paths:=[]string{"/api/v1/commands","/api/v2/commands","/api/v2/modules",
  "/api/v1/events","/api/v1/status","/api/v2/backup/export"}
 for _,path:=range paths{
  for _,method:=range []string{http.MethodGet,http.MethodPost,http.MethodPut,http.MethodDelete}{
   req:=httptest.NewRequest(method,path,nil)
   w:=httptest.NewRecorder()
   isolatedPreviewHandler().ServeHTTP(w,req)
   if w.Code!=http.StatusNotFound{t.Fatalf("%s %s -> %d",method,path,w.Code)}
  }
 }
 req:=httptest.NewRequest(http.MethodPost,"/",strings.NewReader("test"))
 w:=httptest.NewRecorder()
 isolatedPreviewHandler().ServeHTTP(w,req)
 if w.Code!=http.StatusMethodNotAllowed{t.Fatal("POST to preview home accepted")}
}

func TestIsolatedPreviewServesInformationalPage(t *testing.T){
 req:=httptest.NewRequest(http.MethodGet,"/",nil)
 w:=httptest.NewRecorder()
 isolatedPreviewHandler().ServeHTTP(w,req)
 if w.Code!=http.StatusOK {t.Fatal(w.Code)}
 if !strings.Contains(w.Body.String(),"Collaudo isolato") || !strings.Contains(w.Body.String(),"Non accede alla Tesla") {
  t.Fatalf("unexpected UI: %s",w.Body.String())
 }
 if w.Header().Get("Content-Security-Policy")==""{t.Fatal("missing CSP")}
}
