package main

import (
 "context"
 "encoding/json"
 "errors"
 "math"
 "os"
 "path/filepath"
 "sync"
 "time"
)

type V2AlertRule struct{
 ID string `json:"id"`
 VehicleID VehicleID `json:"vehicle_id"`
 Enabled bool `json:"enabled"`
 Condition string `json:"condition"` // soc_below, sentry_panic or away_from_home
 Threshold float64 `json:"threshold,omitempty"`
 CooldownSec int `json:"cooldown_sec"`
 LastFiredAt time.Time `json:"last_fired_at,omitempty"`
 Armed bool `json:"armed"`
}

type V2RuleResult struct{
 ID string
 Vehicle VehicleID
 Title string
 Message string
}

type V2RuleEngine struct{
 mu sync.Mutex
 path string
 rules []V2AlertRule
}

type v2RuleFile struct{
 Schema int `json:"schema"`
 Rules []V2AlertRule `json:"rules"`
}

func OpenV2RuleEngine(dir string)(*V2RuleEngine,error){
 if err:=os.MkdirAll(dir,0700);err!=nil{return nil,err}
 engine:=&V2RuleEngine{path:filepath.Join(dir,"v2-alert-rules.json")}
 b,err:=os.ReadFile(engine.path)
 if errors.Is(err,os.ErrNotExist){return engine,nil}
 if err!=nil{return nil,err}
 var data v2RuleFile
 if err=json.Unmarshal(b,&data);err!=nil{return nil,err}
 if data.Schema!=1{return nil,errors.New("unknown automation schema")}
 engine.rules=data.Rules
 return engine,nil
}

func validV2Rule(rule V2AlertRule) error{
 if rule.VehicleID==""||len(rule.ID)==0||len(rule.ID)>40{
  return errors.New("invalid rule identity")
 }
 if rule.CooldownSec<300||rule.CooldownSec>86400{
  return errors.New("cooldown must be between 5m and 24h")
 }
 switch rule.Condition{
 case "soc_below":
  if math.IsNaN(rule.Threshold)||math.IsInf(rule.Threshold,0)||rule.Threshold<5||rule.Threshold>60{
   return errors.New("SOC threshold must be 5-60%")
  }
 case "sentry_panic", "away_from_home":
  if rule.Threshold!=0{return errors.New("event condition does not take a threshold")}
 default:return errors.New("unknown automation condition")
 }
 return nil
}

func (e *V2RuleEngine) persistLocked(rules []V2AlertRule) error{
 b,err:=json.Marshal(v2RuleFile{Schema:1,Rules:rules})
 if err!=nil{return err}
 f,err:=os.CreateTemp(filepath.Dir(e.path),".v2-rules-*.tmp")
 if err!=nil{return err}
 tmp:=f.Name()
 defer os.Remove(tmp)
 if err=f.Chmod(0600);err!=nil{f.Close();return err}
 if _,err=f.Write(b);err!=nil{f.Close();return err}
 if err=f.Sync();err!=nil{f.Close();return err}
 if err=f.Close();err!=nil{return err}
 if err=os.Rename(tmp,e.path);err!=nil{return err}
 e.rules=rules
 return nil
}

func (e *V2RuleEngine) Rules(vehicle VehicleID) []V2AlertRule{
 e.mu.Lock()
 defer e.mu.Unlock()
 out:=make([]V2AlertRule,0)
 for _,r:=range e.rules{if vehicle==r.VehicleID{out=append(out,r)}}
 return out
}

func (e *V2RuleEngine) Put(vehicle VehicleID,rule V2AlertRule) error{
 rule.VehicleID=vehicle
 if err:=validV2Rule(rule);err!=nil{return err}
 e.mu.Lock()
 defer e.mu.Unlock()
 updated:=append([]V2AlertRule(nil),e.rules...)
 idx:=-1
 for i,r:=range updated{if r.VehicleID==vehicle&&r.ID==rule.ID{idx=i;break}}
 if idx<0&&len(updated)>=100{return errors.New("too many rules")}
 if idx>=0{
  // Keep rate limiting and armed latch state, not caller-supplied timestamps.
  rule.LastFiredAt=updated[idx].LastFiredAt
  rule.Armed=updated[idx].Armed
  updated[idx]=rule
 }else{updated=append(updated,rule)}
 return e.persistLocked(updated)
}

func (e *V2RuleEngine) Delete(vehicle VehicleID,id string) error{
 e.mu.Lock()
 defer e.mu.Unlock()
 out:=make([]V2AlertRule,0,len(e.rules))
 for _,r:=range e.rules{if r.VehicleID!=vehicle||r.ID!=id{out=append(out,r)}}
 if len(out)==len(e.rules){return nil}
 return e.persistLocked(out)
}

// Evaluate makes only notification requests, never a Tesla command.
// SOC alerts are edge-triggered with a 2-point hysteresis and cooldown.
// A panic event must be passed only on a genuine Sentry state transition.
func (e *V2RuleEngine) Evaluate(vehicle VehicleID,kind string,value float64,at time.Time) []V2RuleResult{
 if vehicle==""||at.IsZero()||!finiteNumber(value){return nil}
 if kind=="away_from_home" && value!=0 && value!=1{return nil}
 e.mu.Lock()
 defer e.mu.Unlock()
 next:=append([]V2AlertRule(nil),e.rules...)
 result:=make([]V2RuleResult,0)
 changed:=false
 for i:=range next{
  r:=&next[i]
  if !r.Enabled||r.VehicleID!=vehicle||r.Condition!=kind{continue}
  shouldFire:=false
  switch kind{
  case "soc_below":
   if value>=r.Threshold+2{
    if r.Armed{r.Armed=false;changed=true}
   }else if value<r.Threshold&&!r.Armed{
    shouldFire=true
    r.Armed=true
    changed=true
   }
  case "sentry_panic":
   shouldFire=true
  case "away_from_home":
   // 0 = explicitly reported away; 1 = explicitly reported at home.
   // The caller must also verify the vehicle is parked. Unknown values
   // never arm, disarm, or trigger notifications.
   if value==1 {
    if r.Armed {r.Armed=false;changed=true}
   } else if value==0 && !r.Armed {
    shouldFire=true
    r.Armed=true
    changed=true
   }
  }
  if !shouldFire{continue}
  if !r.LastFiredAt.IsZero()&&at.Sub(r.LastFiredAt)<time.Duration(r.CooldownSec)*time.Second{continue}
  r.LastFiredAt=at
  changed=true
  title,message:="Vigilo: automazione","Evento di sicurezza rilevato."
  if kind=="soc_below"{
   title="Vigilo: batteria sotto soglia"
   message="Livello batteria inferiore alla soglia selezionata."
  }else if kind=="sentry_panic"{
   title="Vigilo: allarme Sentinella"
  }else if kind=="away_from_home"{
   title="Vigilo: parcheggio fuori casa"
   message="La Tesla risulta parcheggiata fuori casa. Controlla che la Sentinella sia attiva."
  }
  result=append(result,V2RuleResult{ID:r.ID,Vehicle:vehicle,Title:title,Message:message})
 }
 if changed{
  if err:=e.persistLocked(next);err!=nil{return nil}
 }
 return result
}

func (a *App) triggerV2Automations(kind string,value float64) {
 if a.v2Rules==nil||a.v2Modules==nil||a.v2Identity==nil{return}
 current:=a.store.Get()
 vehicle:=a.v2Identity.ID(current.VIN)
 if vehicle==""||!a.v2Modules.Enabled(vehicle,ModuleAutomation){return}
 actions:=a.v2Rules.Evaluate(vehicle,kind,value,time.Now().UTC())
 for _,action:=range actions{
  // Existing EventLog is also visible to Android's normal event polling.
  a.events.Add(EvProblem,action.Title,action.Message)
  go func(title,message string,targets []string){
   ctx,cancel:=context.WithTimeout(context.Background(),20*time.Second)
   defer cancel()
   a.notifyAll(ctx,targets,title,message,map[string]any{
    "tag":"vigilo-salute","priority":"high","channel":"Vigilo",
   })
  }(action.Title,action.Message,current.Settings.NotifyTargets)
 }
}
