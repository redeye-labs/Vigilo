package main

import (
 "strings"
 "testing"
 "time"
 "encoding/json"
)

func TestV2PortableExportExcludesSecretsAndOriginalVIN(t *testing.T){
 dir:=t.TempDir()
 hist,err:=OpenV2Archive(dir);if err!=nil{t.Fatal(err)}
 metrics,err:=OpenV2Metrics(dir);if err!=nil{t.Fatal(err)}
 prefs,err:=OpenV2ModuleStore(dir);if err!=nil{t.Fatal(err)}
 vehicle:=VehicleID("private-vehicle-id")
 now:=time.Now().UTC()
 if _,err=hist.ImportLegacyEvents(vehicle,[]Event{{
  ID:1,TS:now.UnixMilli(),Type:EvPanic,Title:"vehicle VIN 5YJ3E1EA",Message:"token=VERY_PRIVATE",
 }});err!=nil{t.Fatal(err)}
 if _,err=metrics.Add(insightSample(vehicle,"RatedRange",278,now));err!=nil{t.Fatal(err)}
 snap,err:=CreateV2PortableExport(vehicle,hist,metrics,prefs)
 if err!=nil{t.Fatal(err)}
 if err=VerifyV2PortableExport(snap);err!=nil{t.Fatal(err)}
 jsonBytes,_:=json.Marshal(snap)
 for _,secret:=range []string{"VERY_PRIVATE","5YJ3E1EA","private-vehicle-id"}{
  if strings.Contains(string(jsonBytes),secret){t.Fatalf("secret %s exposed in backup",secret)}
 }
 if len(snap.Events)!=1||len(snap.Measurements)!=1{t.Fatalf("missing data %+v",snap)}
 if original:=hist.Snapshot(vehicle,10);len(original)!=1||original[0].Message!=""||
    strings.Contains(original[0].Title,"5YJ3E1EA") {
  t.Fatalf("raw event metadata persisted in V2 archive: %+v",original)
 }
 snap.Events[0].Message="tampered"
 if err=VerifyV2PortableExport(snap);err==nil{t.Fatal("altered backup accepted")}
}
