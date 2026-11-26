package main

import (
 "encoding/json"
 "errors"
 "math"
 "os"
 "path/filepath"
 "sync"
 "time"
)

const v2MetricRetention=50000
const v2MetricMinimumInterval=5*time.Minute

// V2Metrics is a transitional, non-secret store for sampled Tesla values.
// Only explicit supported fields are recorded. Disabled modules store nothing.
type V2Metrics struct {
 mu sync.Mutex
 path string
 entries []V2Measurement
}

type v2MetricsData struct {
 Schema int `json:"schema"`
 Entries []V2Measurement `json:"entries"`
}

func OpenV2Metrics(dir string)(*V2Metrics,error){
 if err:=os.MkdirAll(dir,0700);err!=nil{return nil,err}
 v:=&V2Metrics{path:filepath.Join(dir,"v2-measurements.json")}
 b,err:=os.ReadFile(v.path)
 if errors.Is(err,os.ErrNotExist){return v,nil}
 if err!=nil{return nil,err}
 var data v2MetricsData
 if err=json.Unmarshal(b,&data);err!=nil{return nil,err}
 if data.Schema!=1{return nil,errors.New("versione dati telemetrici non supportata")}
 v.entries=data.Entries
 return v,nil
}

// Add updates the archive only for genuine new samples, never inventing
// missing signals. Changes inside the deduplication window are retained.
func (v *V2Metrics) Add(m V2Measurement)(bool,error){
 if err:=m.Validate();err!=nil{return false,err}
 if math.IsNaN(m.Value)||math.IsInf(m.Value,0){return false,errors.New("valore non finito")}
 v.mu.Lock()
 defer v.mu.Unlock()
 for i:=len(v.entries)-1;i>=0;i--{
  old:=v.entries[i]
  if old.VehicleID==m.VehicleID&&old.Signal==m.Signal{
   if old.Value==m.Value && m.ReceivedAt.Sub(old.ReceivedAt)<v2MetricMinimumInterval{return false,nil}
   break
  }
 }
 next:=append(append([]V2Measurement(nil),v.entries...),m)
 if len(next)>v2MetricRetention{next=next[len(next)-v2MetricRetention:]}
 b,err:=json.Marshal(v2MetricsData{Schema:1,Entries:next})
 if err!=nil{return false,err}
 f,err:=os.CreateTemp(filepath.Dir(v.path),".v2-measurements-*.tmp")
 if err!=nil{return false,err}
 temp:=f.Name()
 defer os.Remove(temp)
 if err=f.Chmod(0600);err!=nil{f.Close();return false,err}
 if _,err=f.Write(b);err!=nil{f.Close();return false,err}
 if err=f.Sync();err!=nil{f.Close();return false,err}
 if err=f.Close();err!=nil{return false,err}
 if err=os.Rename(temp,v.path);err!=nil{return false,err}
 v.entries=next
 return true,nil
}

func (v *V2Metrics) Latest(vehicleID VehicleID, signals []string) []V2Measurement{
 v.mu.Lock()
 defer v.mu.Unlock()
 wanted:=map[string]bool{}
 for _,s:=range signals{wanted[s]=true}
 seen:=map[string]bool{}
 out:=[]V2Measurement{}
 for i:=len(v.entries)-1;i>=0;i--{
  x:=v.entries[i]
  if x.VehicleID!=vehicleID || !wanted[x.Signal] || seen[x.Signal]{continue}
  out=append(out,x)
  seen[x.Signal]=true
 }
 return out
}

func (a *App) recordV2Telemetry(field string,val any){
 if a.v2Metrics==nil || a.v2Modules==nil || a.v2Identity==nil{return}
 s:=a.store.Get()
 vin:=a.v2Identity.ID(s.VIN)
 if vin==""{return}
 var module V2Module
 var unit string
 switch field{
 case "Soc":module,unit=ModuleBattery,"percent"
 case "RatedRange":module,unit=ModuleBattery,"km"
 case "NominalFullPackEnergyKwh":module,unit=ModuleBattery,"kWh"
 case "LifetimeEnergyChargedKwh":module,unit=ModuleCharging,"kWh"
 case "LifetimeEnergyUsedDrive":module,unit=ModuleTrips,"kWh"
 case "Odometer":module,unit=ModuleTrips,"mile"
 default:return
 }
 if !a.v2Modules.Enabled(vin,module){return}
 value,ok:=num(val)
 if !ok || math.IsNaN(value)||math.IsInf(value,0){return}
 now:=time.Now().UTC()
 reading:=V2Measurement{
  VehicleID:vin,Signal:field,Value:value,Unit:unit,
  Source:SourceFleetTelemetry,Quality:QualityValid,
  MeasuredAt:now,ReceivedAt:now,
 }
 if _,err:=a.v2Metrics.Add(reading);err!=nil{
  // Do not log the raw reading or VIN. The legacy monitoring must continue.
  return
 }
}
