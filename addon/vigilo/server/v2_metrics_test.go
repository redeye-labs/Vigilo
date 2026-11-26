package main

import (
 "math"
 "testing"
 "time"
)

func TestV2VehicleIdentityStableAcrossRestarts(t *testing.T){
 dir:=t.TempDir()
 a,err:=OpenV2VehicleIdentity(dir);if err!=nil{t.Fatal(err)}
 b,err:=OpenV2VehicleIdentity(dir);if err!=nil{t.Fatal(err)}
 id:=a.ID("5YJ3E1EA7KF000001")
 if id=="" || id!=b.ID("5YJ3E1EA7KF000001"){t.Fatal("unstable ID")}
 if id==VehicleID("5YJ3E1EA7KF000001"){t.Fatal("VIN exposed")}
 if id==a.ID("5YJ3E1EA7KF000002"){t.Fatal("different vehicles share ID")}
 if id!=a.ID(" 5yj3e1ea7kf000001 "){t.Fatal("VIN normalization unstable")}
 if a.ID("")!=""{t.Fatal("empty VIN produces ID")}
}

func TestV2MetricsPersistDeduplicateAndIsolate(t *testing.T){
 dir:=t.TempDir()
 metrics,err:=OpenV2Metrics(dir);if err!=nil{t.Fatal(err)}
 now:=time.Now().UTC()
 m:=V2Measurement{
  VehicleID:"vehicle-a",Signal:"RatedRange",Value:278,Unit:"km",
  Source:SourceFleetTelemetry,Quality:QualityValid,
  MeasuredAt:now,ReceivedAt:now,
 }
 added,err:=metrics.Add(m)
 if err!=nil||!added{t.Fatalf("first insert %v %v",added,err)}
 added,err=metrics.Add(m)
 if err!=nil||added{t.Fatalf("dedup failed %v %v",added,err)}
 m.Value=277
 added,err=metrics.Add(m)
 if err!=nil||!added{t.Fatal("changed measurement lost",err)}
 m.VehicleID="vehicle-b"
 added,err=metrics.Add(m)
 if err!=nil||!added{t.Fatal("second vehicle measurement lost",err)}
 next,err:=OpenV2Metrics(dir);if err!=nil{t.Fatal(err)}
 a:=next.Latest("vehicle-a",[]string{"RatedRange"})
 b:=next.Latest("vehicle-b",[]string{"RatedRange"})
 if len(a)!=1||len(b)!=1||a[0].VehicleID!="vehicle-a"||b[0].VehicleID!="vehicle-b"{
  t.Fatalf("vehicle isolation failed: a=%v b=%v",a,b)
 }
}

func TestV2MetricsRejectInvalid(t *testing.T){
 metrics,err:=OpenV2Metrics(t.TempDir());if err!=nil{t.Fatal(err)}
 now:=time.Now()
 m:=V2Measurement{
  VehicleID:"car",Signal:"Soc",Value:math.NaN(),Unit:"percent",
  Source:SourceFleetTelemetry,Quality:QualityValid,
  MeasuredAt:now,ReceivedAt:now,
 }
 if _,err:=metrics.Add(m);err==nil{t.Fatal("NaN stored")}
 m.Value=66
 m.Source=SourceCalculated
 if _,err:=metrics.Add(m);err==nil{t.Fatal("calculation without method stored")}
}
