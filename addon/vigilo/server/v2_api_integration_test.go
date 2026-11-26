package main

import (
 "bytes"
 "encoding/json"
 "net/http"
 "net/http/httptest"
 "testing"
)

func v2AuthedJSON(api *V2PublicAPI,token,method,path,body string) *httptest.ResponseRecorder {
 req:=httptest.NewRequest(method,path,bytes.NewBufferString(body))
 req.Header.Set("Authorization","Bearer "+token)
 req.Header.Set("Content-Type","application/json")
 w:=httptest.NewRecorder()
 api.ServeHTTP(w,req)
 return w
}

func TestV2ModuleOptInAndTariffRoundTrip(t *testing.T){
 api,token:=newTestV2PublicAPI(t)
 w:=v2AuthedJSON(api,token,http.MethodGet,"/api/v2/charging/sessions","")
 if w.Code!=http.StatusForbidden{t.Fatalf("charging must be disabled by default, got %d",w.Code)}
 w=v2AuthedJSON(api,token,http.MethodPut,"/api/v2/modules",`{"module":"charging","enabled":true}`)
 if w.Code!=http.StatusOK{t.Fatal(w.Code,w.Body.String())}
 w=v2AuthedJSON(api,token,http.MethodPut,"/api/v2/charging/tariff",`{"price_eur_per_kwh":0.32}`)
 if w.Code!=http.StatusOK{t.Fatal(w.Code,w.Body.String())}
 w=v2AuthedJSON(api,token,http.MethodGet,"/api/v2/charging/tariff","")
 var result struct{Price *float64 `json:"price_eur_per_kwh"`}
 if err:=json.Unmarshal(w.Body.Bytes(),&result);err!=nil{t.Fatal(err)}
 if result.Price==nil||*result.Price!=0.32{t.Fatalf("tariff mismatch: %+v",result)}
 w=v2AuthedJSON(api,token,http.MethodPut,"/api/v2/charging/tariff",`{"price_eur_per_kwh":-2}`)
 if w.Code!=http.StatusBadRequest{t.Fatal("unsafe tariff accepted")}
}

func TestV2BackupEndpointRequiresOptInAndStripsMessages(t *testing.T){
 api,token:=newTestV2PublicAPI(t)
 w:=v2AuthedJSON(api,token,http.MethodGet,"/api/v2/backup/export","")
 if w.Code!=http.StatusForbidden{t.Fatal("backup enabled by default")}
 w=v2AuthedJSON(api,token,http.MethodPut,"/api/v2/modules",`{"module":"backup","enabled":true}`)
 if w.Code!=http.StatusOK{t.Fatal(w.Body.String())}
 w=v2AuthedJSON(api,token,http.MethodGet,"/api/v2/backup/export","")
 if w.Code!=http.StatusOK{t.Fatalf("export status=%d, body=%s",w.Code,w.Body.String())}
 var backup V2PortableExport
 if err:=json.Unmarshal(w.Body.Bytes(),&backup);err!=nil{t.Fatal(err)}
 if err:=VerifyV2PortableExport(backup);err!=nil{t.Fatal(err)}
}

func TestV2AutomationEndpointRequiresAuthentication(t *testing.T){
 api,token:=newTestV2PublicAPI(t)
 dir:=t.TempDir()
 rules,err:=OpenV2RuleEngine(dir);if err!=nil{t.Fatal(err)}
 api.rules=rules
 w:=v2AuthedJSON(api,"invalid",http.MethodPut,"/api/v2/automations",`{"id":"soc-low","condition":"soc_below","threshold":20,"cooldown_sec":3600}`)
 if w.Code!=http.StatusUnauthorized{t.Fatal("unauthorized rule permitted")}
 w=v2AuthedJSON(api,token,http.MethodPut,"/api/v2/automations",`{"id":"soc-low","condition":"soc_below","threshold":20,"cooldown_sec":3600,"enabled":true}`)
 if w.Code!=http.StatusOK{t.Fatal(w.Code,w.Body.String())}
 if got:=rules.Rules(api.identity.ID("test-vehicle-1"));len(got)!=1||!got[0].Enabled{
  t.Fatalf("rule not scoped to active car: %+v",got)
 }
 w=v2AuthedJSON(api,token,http.MethodPut,"/api/v2/automations",`{"id":"x","condition":"remote_start_drive","cooldown_sec":3600}`)
 if w.Code!=http.StatusBadRequest{t.Fatal("unsafe automatic car control accepted")}
}
