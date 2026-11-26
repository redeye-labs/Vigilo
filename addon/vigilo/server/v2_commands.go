package main

import (
 "context"
 "errors"
 "fmt"
)

type V2CommandSpec struct {
 Endpoint string
 Body map[string]any
 Label string
 Sensitive bool
}

// These are intentionally NOT added to the legacy commandMap. They can only
// be reached through opt-in, confirmed Vigilo 2 requests.
var v2AdvancedCommands = map[string]V2CommandSpec{
 "climate_on": {"auto_conditioning_start",nil,"Climatizzazione attivata",false},
 "climate_off": {"auto_conditioning_stop",nil,"Climatizzazione disattivata",false},
 "charge_start": {"charge_start",nil,"Ricarica avviata",false},
 "charge_stop": {"charge_stop",nil,"Ricarica interrotta",false},
 "charge_port_open": {"charge_port_door_open",nil,"Sportellino ricarica aperto",false},
 "charge_port_close": {"charge_port_door_close",nil,"Sportellino ricarica chiuso",false},
 "trunk_rear": {"actuate_trunk",map[string]any{"which_trunk":"rear"},"Bagagliaio posteriore azionato",true},
 "trunk_front": {"actuate_trunk",map[string]any{"which_trunk":"front"},"Bagagliaio anteriore azionato",true},
}

func ValidateV2AdvancedCommand(name string, confirmed bool) (V2CommandSpec,error){
 spec,ok:=v2AdvancedCommands[name]
 if !ok{return V2CommandSpec{},errors.New("comando Vigilo 2 sconosciuto")}
 if !confirmed {return V2CommandSpec{},errors.New("conferma esplicita obbligatoria")}
 return spec,nil
}

// AdvancedCommand does not wake the car or silently retry commands: those
// decisions are reserved to the user. The calling handler must check opt-in,
// authentication and current vehicle connection before invoking this.
func (t *Tesla) AdvancedCommand(ctx context.Context, vin, name string, confirmed bool) (string,error){
 spec,err:=ValidateV2AdvancedCommand(name,confirmed)
 if err!=nil{return "",err}
 if vin==""{return "",errors.New("auto non selezionata")}
 body:=spec.Body
 if body==nil{body=map[string]any{}}
 result,err:=t.call(ctx,"POST","/api/1/vehicles/"+vin+"/command/"+spec.Endpoint,body)
 if err!=nil{return "",err}
 response,_:=result["response"].(map[string]any)
 if response!=nil {
  if ok,exists:=response["result"].(bool);exists&&!ok {
   return "",fmt.Errorf("Tesla ha rifiutato il comando: %s",str(response["reason"]))
  }
 }
 return spec.Label,nil
}
