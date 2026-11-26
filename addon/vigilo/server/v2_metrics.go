package main

import (
 "bufio"
 "encoding/json"
 "errors"
 "math"
 "os"
 "path/filepath"
 "sync"
 "time"
)

const v2MetricRetention=60000
const v2MetricKeep=30000
const v2MetricMinimumInterval=5*time.Minute

// V2Metrics uses a newline-delimited append-only journal and an atomic
// compacted snapshot. Unlike a full JSON rewrite, this is bounded work per
// Tesla telemetry update, suitable for modest HA/Docker installations.
type V2Metrics struct {
 mu sync.Mutex
 path string
 journal string
 seq uint64
 entries []V2Measurement
}

type v2MetricsData struct {
 Schema int `json:"schema"`
 LastSeq uint64 `json:"last_seq,omitempty"`
 Entries []V2Measurement `json:"entries"`
}

type v2MetricLogRecord struct{
 Seq uint64 `json:"seq"`
 Reading V2Measurement `json:"reading"`
}

func OpenV2Metrics(dir string)(*V2Metrics,error){
 if err:=os.MkdirAll(dir,0700);err!=nil{return nil,err}
 v:=&V2Metrics{path:filepath.Join(dir,"v2-measurements.json"),
              journal:filepath.Join(dir,"v2-measurements.ndjson")}
 b,err:=os.ReadFile(v.path)
 if err==nil{
  var data v2MetricsData
  if err=json.Unmarshal(b,&data);err!=nil{return nil,err}
  if data.Schema!=1{return nil,errors.New("versione dati telemetrici non supportata")}
  v.entries=data.Entries
  v.seq=data.LastSeq
 }else if !errors.Is(err,os.ErrNotExist){return nil,err}

 logFile,err:=os.Open(v.journal)
 if errors.Is(err,os.ErrNotExist){return v,nil}
 if err!=nil{return nil,err}
 defer logFile.Close()
 scanner:=bufio.NewScanner(logFile)
 scanner.Buffer(make([]byte,4096),1<<20)
 for scanner.Scan(){
  var record v2MetricLogRecord
  if err=json.Unmarshal(scanner.Bytes(),&record);err!=nil{
   // Malformed journal is never discarded silently: the operator must
   // restore the protected snapshot before allowing more writes.
   return nil,errors.New("registro misure Vigilo 2 danneggiato")
  }
  if record.Seq>v.seq{
   if err=record.Reading.Validate();err!=nil{return nil,err}
   v.entries=append(v.entries,record.Reading)
   v.seq=record.Seq
  }
 }
 if err=scanner.Err();err!=nil{return nil,err}
 return v,nil
}

func (v *V2Metrics) compactLocked() error{
 if len(v.entries)<v2MetricRetention{return nil}
 keep:=append([]V2Measurement(nil),v.entries[len(v.entries)-v2MetricKeep:]...)
 data,err:=json.Marshal(v2MetricsData{Schema:1,LastSeq:v.seq,Entries:keep})
 if err!=nil{return err}
 f,err:=os.CreateTemp(filepath.Dir(v.path),".v2-metrics-*.tmp")
 if err!=nil{return err}
 temp:=f.Name()
 defer os.Remove(temp)
 if err=f.Chmod(0600);err!=nil{f.Close();return err}
 if _,err=f.Write(data);err!=nil{f.Close();return err}
 if err=f.Sync();err!=nil{f.Close();return err}
 if err=f.Close();err!=nil{return err}
 if err=os.Rename(temp,v.path);err!=nil{return err}
 // Snapshot is already durable. If truncation fails, startup replay skips
 // journal entries at or below LastSeq, avoiding duplicates.
 if err=os.WriteFile(v.journal,nil,0600);err!=nil{return err}
 v.entries=keep
 return nil
}

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
 record:=v2MetricLogRecord{Seq:v.seq+1,Reading:m}
 line,err:=json.Marshal(record)
 if err!=nil{return false,err}
 file,err:=os.OpenFile(v.journal,os.O_WRONLY|os.O_APPEND|os.O_CREATE,0600)
 if err!=nil{return false,err}
 line=append(line,'\n')
 _,err=file.Write(line)
 if err==nil{err=file.Sync()}
 if closing:=file.Close();err==nil{err=closing}
 if err!=nil{return false,err}
 v.seq++
 v.entries=append(v.entries,m)
 if err:=v.compactLocked();err!=nil{
  // The complete journal remains available after a failed compaction.
  return true,err
 }
 return true,nil
}

func (v *V2Metrics) History(vehicleID VehicleID, signal string, since time.Time, limit int) []V2Measurement {
 v.mu.Lock()
 defer v.mu.Unlock()
 if vehicleID=="" || signal=="" {return nil}
 if limit<=0 || limit>20000 {limit=2000}
 out:=make([]V2Measurement,0)
 for i:=len(v.entries)-1;i>=0&&len(out)<limit;i--{
  m:=v.entries[i]
  if m.VehicleID==vehicleID&&m.Signal==signal&&!m.ReceivedAt.Before(since){out=append(out,m)}
 }
 for i,j:=0,len(out)-1;i<j;i,j=i+1,j-1{out[i],out[j]=out[j],out[i]}
 return out
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
  if x.ReceivedAt.IsZero() || time.Since(x.ReceivedAt)>20*time.Minute {
   // A saved sample is real historical evidence, not a live vehicle status.
   x.Quality=QualityStale
  }
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
 case "NominalFullPackEnergyKwh", "EnergyRemaining":module,unit=ModuleBattery,"kWh"
 case "LifetimeEnergyChargedKwh", "ACChargingEnergyIn", "DCChargingEnergyIn":module,unit=ModuleCharging,"kWh"
 case "DCChargingPower":module,unit=ModuleCharging,"kW"
 case "Odometer":module,unit=ModuleTrips,"mile"
 case "LifetimeEnergyUsed":module,unit=ModuleTrips,"kWh"
 case "TpmsPressureFl", "TpmsPressureFr", "TpmsPressureRl", "TpmsPressureRr":module,unit=ModuleDiagnostics,"bar"
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
