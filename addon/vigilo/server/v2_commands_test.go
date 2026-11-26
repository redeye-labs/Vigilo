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
