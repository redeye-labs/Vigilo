package main

import (
 "os"
 "path/filepath"
 "testing"
)

func TestV2ModulePreferencesPersistAndIsolateVehicles(t *testing.T) {
 dir:=t.TempDir()
 s,err:=OpenV2ModuleStore(dir);if err!=nil{t.Fatal(err)}
 if s.Enabled("car-a",ModuleBattery) || s.Enabled("car-b",ModuleBattery) { t.Fatal("modules must be opt in") }
 if err:=s.Set("",ModuleBattery,true);err!=nil{t.Fatal(err)}
 if !s.Enabled("car-a",ModuleBattery){t.Fatal("global choice missing")}
 if err:=s.Set("car-a",ModuleBattery,false);err!=nil{t.Fatal(err)}
 if s.Enabled("car-a",ModuleBattery) || !s.Enabled("car-b",ModuleBattery) {t.Fatal("vehicle-specific choice not isolated")}
 if err:=s.Set("car-a",ModuleDiagnostics,true);err!=nil{t.Fatal(err)}
 if s.Enabled("car-b",ModuleDiagnostics){t.Fatal("vehicle choice leaked")}
 next,err:=OpenV2ModuleStore(dir);if err!=nil{t.Fatal(err)}
 if next.Enabled("car-a",ModuleBattery) || !next.Enabled("car-b",ModuleBattery) || !next.Enabled("car-a",ModuleDiagnostics){t.Fatal("settings lost after restart")}
 info,err:=os.Stat(filepath.Join(dir,"v2-modules.json"))
 if err!=nil||info.Mode().Perm()!=0600{t.Fatalf("permissions: %v %v",info,err)}
}

func TestV2ModulePreferencesRejectUnknownAndInvalidSchema(t *testing.T) {
 dir:=t.TempDir()
 s,err:=OpenV2ModuleStore(dir);if err!=nil{t.Fatal(err)}
 if err=s.Set("",V2Module("not-real"),true);err==nil{t.Fatal("unknown module accepted")}
 path:=filepath.Join(dir,"v2-modules.json")
 if err=os.WriteFile(path,[]byte(`{"schema":99}`),0600);err!=nil{t.Fatal(err)}
 if _,err=OpenV2ModuleStore(dir);err==nil{t.Fatal("unknown future schema accepted")}
}
