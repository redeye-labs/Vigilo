package main

import (
 "testing"
 "time"
)

func TestV2AdvancedCommandsRequireFreshParkedTelemetry(t *testing.T) {
 now:=time.Date(2026,11,26,21,0,0,0,time.UTC)
 state:=State{}
 state.Vehicle.Online=true
 state.Vehicle.ConnState="online"
 state.Vehicle.Gear="P"
 state.Vehicle.LastTelemetry=now.Add(-time.Minute).UnixMilli()
 if err:=validateV2CommandVehicle(state,now);err!=nil{t.Fatal(err)}
 for _,gear:=range []string{"D","R","N",""}{
  state.Vehicle.Gear=gear
  if err:=validateV2CommandVehicle(state,now);err==nil{
   t.Fatalf("unsafe gear %q accepted",gear)
  }
 }
 state.Vehicle.Gear="P"
 state.Vehicle.LastTelemetry=now.Add(-3*time.Minute).UnixMilli()
 if err:=validateV2CommandVehicle(state,now);err==nil{t.Fatal("stale telemetry accepted")}
 state.Vehicle.LastTelemetry=0
 if err:=validateV2CommandVehicle(state,now);err==nil{t.Fatal("unknown telemetry accepted")}
 state.Vehicle.LastTelemetry=now.Add(time.Minute).UnixMilli()
 if err:=validateV2CommandVehicle(state,now);err==nil{t.Fatal("future telemetry accepted")}
 state.Vehicle.LastTelemetry=now.UnixMilli()
 state.Vehicle.ConnState="asleep"
 if err:=validateV2CommandVehicle(state,now);err==nil{t.Fatal("sleeping vehicle accepted")}
 state.Vehicle.ConnState="online"
 state.Vehicle.Online=false
 if err:=validateV2CommandVehicle(state,now);err==nil{t.Fatal("offline vehicle accepted")}
}
