package main

import (
 "encoding/json"
 "net/http"
 "net/http/httptest"
 "testing"
 "time"
)

func newTestV2PublicAPI(t *testing.T) (*V2PublicAPI,string) {
 t.Helper()
 dir:=t.TempDir()
 state,err:=NewStore(dir);if err!=nil{t.Fatal(err)}
 if err=state.Update(func(s *State){s.VIN="test-vehicle-1";s.Vehicle.ConnState="asleep"});err!=nil{t.Fatal(err)}
 history,err:=OpenV2Archive(dir);if err!=nil{t.Fatal(err)}
 modules,err:=OpenV2ModuleStore(dir);if err!=nil{t.Fatal(err)}
 return &V2PublicAPI{state:state,history:history,modules:modules,events:NewEventLog(dir)},state.Get().PairingToken
}

func v2Request(api *V2PublicAPI,token,method,path string) *httptest.ResponseRecorder {
 req:=httptest.NewRequest(method,path,nil)
 if token!=""{req.Header.Set("Authorization","Bearer "+token)}
 w:=httptest.NewRecorder()
 api.ServeHTTP(w,req)
 return w
}

func TestV2APIRejectsUnauthorizedAndMutatingRequests(t *testing.T) {
 api,token:=newTestV2PublicAPI(t)
 if got:=v2Request(api,"",http.MethodGet,"/api/v2/diagnostics").Code;got!=http.StatusUnauthorized{t.Fatal(got)}
 if got:=v2Request(api,"invalid",http.MethodGet,"/api/v2/diagnostics").Code;got!=http.StatusUnauthorized{t.Fatal(got)}
 if got:=v2Request(api,token,http.MethodPost,"/api/v2/diagnostics").Code;got!=http.StatusMethodNotAllowed{t.Fatal(got)}
 if got:=v2Request(api,token,http.MethodGet,"/api/v2/not-defined").Code;got!=http.StatusNotFound{t.Fatal(got)}
}

func TestV2APIDiagnosticsAndCapabilities(t *testing.T) {
 api,token:=newTestV2PublicAPI(t)
 w:=v2Request(api,token,http.MethodGet,"/api/v2/diagnostics")
 if w.Code!=http.StatusOK{t.Fatal(w.Code)}
 var body struct {
  OK bool `json:"ok"`
  Telemetry V2Diagnostic `json:"telemetry"`
  Permission string `json:"permission_status"`
 }
 if err:=json.Unmarshal(w.Body.Bytes(),&body);err!=nil{t.Fatal(err)}
 if !body.OK || body.Telemetry.Status!=DiagnosticSleeping || body.Permission!="not_verified" {
  t.Fatalf("inaccurate diagnostics: %+v",body)
 }
 w=v2Request(api,token,http.MethodGet,"/api/v2/capabilities")
 if w.Code!=http.StatusOK{t.Fatal(w.Code)}
 if err:=json.Unmarshal(w.Body.Bytes(),&map[string]any{});err!=nil{t.Fatal(err)}
}

func TestV2APISecurityDoesNotMixEvents(t *testing.T){
 api,token:=newTestV2PublicAPI(t)
 ts:=time.Now().Add(-time.Hour).UnixMilli()
 if _,err:=api.history.ImportLegacyEvents("other-vehicle",[]Event{{ID:100,TS:ts,Type:EvPanic}});err!=nil{t.Fatal(err)}
 if _,err:=api.history.ImportLegacyEvents("test-vehicle-1",[]Event{{ID:101,TS:ts,Type:EvAware}});err!=nil{t.Fatal(err)}
 w:=v2Request(api,token,http.MethodGet,"/api/v2/security/incidents?limit=10")
 if w.Code!=http.StatusOK{t.Fatal(w.Code)}
 var out struct{ Incidents []V2IncidentSummary `json:"incidents"` }
 if err:=json.Unmarshal(w.Body.Bytes(),&out);err!=nil{t.Fatal(err)}
 if len(out.Incidents)!=1||out.Incidents[0].VehicleID!="test-vehicle-1"{
  t.Fatalf("leaked events from another vehicle: %+v",out)
 }
}
