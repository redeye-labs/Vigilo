package main

import (
 "encoding/json"
 "errors"
 "fmt"
 "math"
 "os"
 "path/filepath"
 "sync"
)

type V2ModuleStore struct {
 mu sync.Mutex
 path string
 data V2ModuleStoreData
}

type V2ModuleStoreData struct {
 Schema int `json:"schema"`
 Global V2ModulePreferences `json:"global"`
 PerVehicle map[VehicleID]V2ModulePreferences `json:"per_vehicle"`
 TariffsEUR map[VehicleID]float64 `json:"tariffs_eur_per_kwh,omitempty"`
}

func OpenV2ModuleStore(dir string) (*V2ModuleStore,error) {
 if err:=os.MkdirAll(dir,0700);err!=nil{return nil,err}
 s:=&V2ModuleStore{
  path:filepath.Join(dir,"v2-modules.json"),
  data:V2ModuleStoreData{Schema:1,PerVehicle:map[VehicleID]V2ModulePreferences{},TariffsEUR:map[VehicleID]float64{}},
 }
 b,err:=os.ReadFile(s.path)
 if errors.Is(err,os.ErrNotExist){return s,nil}
 if err!=nil{return nil,err}
 if err:=json.Unmarshal(b,&s.data);err!=nil{return nil,fmt.Errorf("preferenze moduli non leggibili: %w",err)}
 if s.data.Schema!=1{return nil,fmt.Errorf("versione preferenze moduli non supportata: %d",s.data.Schema)}
 if s.data.PerVehicle==nil{s.data.PerVehicle=map[VehicleID]V2ModulePreferences{}}
 if s.data.TariffsEUR==nil{s.data.TariffsEUR=map[VehicleID]float64{}}
 return s,nil
}

func copyPrefs(p V2ModulePreferences) V2ModulePreferences {
 x:=V2ModulePreferences{Enabled:make(map[V2Module]bool,len(p.Enabled))}
 for k,v:=range p.Enabled{x.Enabled[k]=v}
 return x
}

func (s *V2ModuleStore) Enabled(vehicleID VehicleID,m V2Module) bool {
 s.mu.Lock()
 defer s.mu.Unlock()
 if vehicleID!="" {
  if p,ok:=s.data.PerVehicle[vehicleID];ok {
   if v,set:=p.Enabled[m];set{return v && ValidV2Module(m)}
  }
 }
 return s.data.Global.IsEnabled(m)
}

// Set writes a module preference atomically, without modifying the live
// Vigilo settings. In V2, an explicit per-vehicle choice overrides global.
func (s *V2ModuleStore) Set(vehicleID VehicleID,m V2Module,enabled bool) error {
 if !ValidV2Module(m){return errors.New("modulo sconosciuto")}
 s.mu.Lock()
 defer s.mu.Unlock()
 next:=V2ModuleStoreData{
  Schema:1,Global:copyPrefs(s.data.Global),
  PerVehicle:make(map[VehicleID]V2ModulePreferences,len(s.data.PerVehicle)),
  TariffsEUR:make(map[VehicleID]float64,len(s.data.TariffsEUR)),
 }
 for k,v:=range s.data.PerVehicle{next.PerVehicle[k]=copyPrefs(v)}
 for k,v:=range s.data.TariffsEUR{next.TariffsEUR[k]=v}
 if vehicleID=="" {
  if err:=next.Global.Set(m,enabled);err!=nil{return err}
 }else{
  p:=next.PerVehicle[vehicleID]
  if err:=p.Set(m,enabled);err!=nil{return err}
  next.PerVehicle[vehicleID]=p
 }
 b,err:=json.Marshal(next)
 if err!=nil{return err}
 f,err:=os.CreateTemp(filepath.Dir(s.path),".v2-modules-*.tmp")
 if err!=nil{return err}
 tmp:=f.Name()
 defer os.Remove(tmp)
 if err=f.Chmod(0600);err!=nil{f.Close();return err}
 if _,err=f.Write(b);err!=nil{f.Close();return err}
 if err=f.Sync();err!=nil{f.Close();return err}
 if err=f.Close();err!=nil{return err}
 if err=os.Rename(tmp,s.path);err!=nil{return err}
 s.data=next
 return nil
}

func (s *V2ModuleStore) Tariff(vehicle VehicleID) *float64 {
 s.mu.Lock()
 defer s.mu.Unlock()
 if vehicle==""{return nil}
 x,ok:=s.data.TariffsEUR[vehicle]
 if !ok{return nil}
 return &x
}

// SetTariff persists an explicit user-entered price in EUR/kWh.
// There is no default price: without user input, costs are unavailable.
func (s *V2ModuleStore) SetTariff(vehicle VehicleID, price *float64) error{
 if vehicle==""{return errors.New("vehicle_id missing")}
 if price!=nil&&(*price<0||*price>10||math.IsNaN(*price)||math.IsInf(*price,0)){
  return errors.New("invalid price")
 }
 s.mu.Lock()
 defer s.mu.Unlock()
 next:=V2ModuleStoreData{
  Schema:1,Global:copyPrefs(s.data.Global),
  PerVehicle:make(map[VehicleID]V2ModulePreferences,len(s.data.PerVehicle)),
  TariffsEUR:make(map[VehicleID]float64,len(s.data.TariffsEUR)),
 }
 for k,v:=range s.data.PerVehicle{next.PerVehicle[k]=copyPrefs(v)}
 for k,v:=range s.data.TariffsEUR{next.TariffsEUR[k]=v}
 if price==nil{delete(next.TariffsEUR,vehicle)}else{next.TariffsEUR[vehicle]=*price}
 b,err:=json.Marshal(next)
 if err!=nil{return err}
 f,err:=os.CreateTemp(filepath.Dir(s.path),".v2-modules-*.tmp")
 if err!=nil{return err}
 tmp:=f.Name()
 defer os.Remove(tmp)
 if err=f.Chmod(0600);err!=nil{f.Close();return err}
 if _,err=f.Write(b);err!=nil{f.Close();return err}
 if err=f.Sync();err!=nil{f.Close();return err}
 if err=f.Close();err!=nil{return err}
 if err=os.Rename(tmp,s.path);err!=nil{return err}
 s.data=next
 return nil
}
