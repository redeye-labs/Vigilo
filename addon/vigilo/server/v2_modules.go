package main

import (
 "encoding/json"
 "errors"
 "fmt"
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
}

func OpenV2ModuleStore(dir string) (*V2ModuleStore,error) {
 if err:=os.MkdirAll(dir,0700);err!=nil{return nil,err}
 s:=&V2ModuleStore{
  path:filepath.Join(dir,"v2-modules.json"),
  data:V2ModuleStoreData{Schema:1,PerVehicle:map[VehicleID]V2ModulePreferences{}},
 }
 b,err:=os.ReadFile(s.path)
 if errors.Is(err,os.ErrNotExist){return s,nil}
 if err!=nil{return nil,err}
 if err:=json.Unmarshal(b,&s.data);err!=nil{return nil,fmt.Errorf("preferenze moduli non leggibili: %w",err)}
 if s.data.Schema!=1{return nil,fmt.Errorf("versione preferenze moduli non supportata: %d",s.data.Schema)}
 if s.data.PerVehicle==nil{s.data.PerVehicle=map[VehicleID]V2ModulePreferences{}}
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
 }
 for k,v:=range s.data.PerVehicle{next.PerVehicle[k]=copyPrefs(v)}
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
