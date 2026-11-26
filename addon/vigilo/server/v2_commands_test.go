package main

import (
 "net/http"
 "testing"
)

func TestV2AdvancedCommandsValidateExplicitConfirmation(t *testing.T) {
 for name,spec:=range v2AdvancedCommands{
  if spec.Endpoint==""{t.Fatalf("empty endpoint for %s",name)}
  if _,err:=ValidateV2AdvancedCommand(name,false);err==nil{
   t.Fatalf("command %s accepted without confirmation",name)
  }
  if _,err:=ValidateV2AdvancedCommand(name,true);err!=nil{t.Fatal(err)}
 }
 if _,err:=ValidateV2AdvancedCommand("remote_start_drive",true);err==nil{
  t.Fatal("dangerous command mistakenly exposed")
 }
}

func TestV2AdvancedAPIDisabledUntilExplicitOptIn(t *testing.T){
 api,token:=newTestV2PublicAPI(t)
 w:=v2Request(api,token,http.MethodPost,"/api/v2/commands")
 if w.Code!=http.StatusForbidden{t.Fatalf("expected disabled module, got %d",w.Code)}
 // An unpaired phone must not be able to enable modules.
 w=v2Request(api,"invalid",http.MethodPut,"/api/v2/modules")
 if w.Code!=http.StatusUnauthorized{t.Fatalf("expected auth rejection, got %d",w.Code)}
}

func TestV2ParameterizedTeslaCommandsAreBounded(t *testing.T){
 valid:=[]struct{Name string;Value float64;Path string}{
  {"charge_limit",80,"set_charge_limit"},
  {"climate_temperature",21.5,"set_temps"},
  {"charging_amps",16,"set_charging_amps"},
 }
 for _,tc:=range valid{
  path,body,_,err:=ValidateV2ParameterCommand(tc.Name,tc.Value,true)
  if err!=nil || path!=tc.Path || body==nil {
   t.Fatalf("expected bounded command %s: %s %v",tc.Name,path,err)
  }
  if _,_,_,err:=ValidateV2ParameterCommand(tc.Name,tc.Value,false);err==nil{
   t.Fatal("confirmation bypassed")
  }
 }
 invalid:=[]struct{Name string;Value float64}{
  {"charge_limit",49},{"charge_limit",101},{"charge_limit",80.5},
  {"climate_temperature",31},{"climate_temperature",14},
  {"charging_amps",40},{"charging_amps",16.5},
  {"remote_start_drive",1},
 }
 for _,tc:=range invalid {
  if _,_,_,err:=ValidateV2ParameterCommand(tc.Name,tc.Value,true);err==nil{
   t.Fatalf("invalid Tesla command accepted: %+v",tc)
  }
 }
}
