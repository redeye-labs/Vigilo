package main

import (
 "math"
 "testing"
 "time"
)

func TestBatteryCapacityTrendRequiresThirtyDaysAndBMSOnly(t *testing.T){
 start:=time.Date(2026,1,1,12,0,0,0,time.UTC)
 mk:=func(days int,v float64) V2Measurement {
  return insightSample("a","NominalFullPackEnergyKwh",v,start.Add(time.Duration(days)*24*time.Hour))
 }
 data:=[]V2Measurement{
  mk(0,52.5),mk(1,52.4),mk(2,52.6),mk(31,52.0),mk(32,52.1),mk(33,52.0),
 }
 trend,ok:=BatteryCapacityTrend("a",data)
 if !ok||trend.FirstBMSKWh!=52.5||trend.LatestBMSKWh!=52.0||
    math.Abs(trend.PercentChange+0.95)>0.05{
  t.Fatalf("invalid BMS trend: %+v ok=%v",trend,ok)
 }
 if _,ok=BatteryCapacityTrend("b",data);ok{t.Fatal("other vehicle data leaked")}
 if _,ok=BatteryCapacityTrend("a",data[:5]);ok{t.Fatal("insufficient samples accepted")}
 short:=[]V2Measurement{mk(0,52.5),mk(1,52.4),mk(2,52.6),mk(3,52.5),mk(4,52.4),mk(5,52.5)}
 if _,ok=BatteryCapacityTrend("a",short);ok{t.Fatal("short history reported as degradation")}
 unstable:=[]V2Measurement{mk(0,52.5),mk(1,60),mk(2,52.4),mk(31,52.0),mk(32,52.1),mk(33,52.0)}
 if _,ok=BatteryCapacityTrend("a",unstable);ok{t.Fatal("unstable BMS baseline accepted")}
}
