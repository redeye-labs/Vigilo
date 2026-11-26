package main

import (
 "math"
 "testing"
 "time"
)

func TestBatteryHealthRequiresRealComparableBaseline(t *testing.T) {
 current := 52.0
 r := BatteryHealthEstimate(&current, nil)
 if r.HealthPercent != nil || r.DegradationPercent != nil || r.MeasuredFullPackKWh == nil { t.Fatalf("invented SOH: %+v", r) }
 baseline := 55.0
 r = BatteryHealthEstimate(&current, &baseline)
 if r.HealthPercent == nil || math.Abs(*r.HealthPercent-94.54545) > 0.01 { t.Fatalf("unexpected SOH: %+v", r) }
 r = BatteryHealthEstimate(nil, &baseline)
 if r.HealthPercent != nil { t.Fatal("missing BMS must not return health") }
 absurdBaseline := 10.0
 r = BatteryHealthEstimate(&current, &absurdBaseline)
 if r.HealthPercent != nil { t.Fatal("incoherent BMS measurements accepted") }
}

func TestChargeCostExplicitlyEstimated(t *testing.T) {
 r, err := EstimateChargeCost(21.5, 0.30)
 if err != nil || r.EstimatedCostEuro != 6.45 || r.Method == "" { t.Fatalf("unexpected cost: %+v %v",r,err) }
 if _, err = EstimateChargeCost(math.NaN(),0.2); err == nil { t.Fatal("NaN accepted") }
 if _, err = EstimateChargeCost(20,-0.2); err == nil { t.Fatal("negative tariff accepted") }
}

func TestTripEfficiencyNeedsMeasuredValues(t *testing.T) {
 v, err := CalculateTripEfficiency(100, 14.5)
 if err != nil || v.WhPerKm != 145 || v.KWhPer100Km != 14.5 { t.Fatalf("unexpected efficiency: %+v %v",v,err) }
 if _,err = CalculateTripEfficiency(0, 14);err == nil { t.Fatal("zero distance accepted") }
 if _,err = CalculateTripEfficiency(100, math.NaN());err == nil { t.Fatal("NaN energy accepted") }
}

func TestDiagnosticsDistinguishesFailures(t *testing.T) {
 now := time.Date(2026,11,26,22,0,0,0,time.UTC)
 recent := now.Add(-2*time.Minute)
 stale := now.Add(-time.Hour)
 cases := []struct{
  name string
  server, permission, asleep bool
  last time.Time
  want V2DiagnosticStatus
 }{
  {"offline",false,true,false,recent,DiagnosticServerOffline},
  {"scope",true,false,false,recent,DiagnosticMissingPermission},
  {"sleep",true,true,true,stale,DiagnosticSleeping},
  {"stale",true,true,false,stale,DiagnosticStale},
  {"missing",true,true,false,time.Time{},DiagnosticNoData},
  {"ok",true,true,false,recent,DiagnosticOK},
 }
 for _,tc := range cases { t.Run(tc.name,func(t *testing.T){
  d:=DiagnoseConnection(tc.server,tc.permission,tc.asleep,tc.last,now,10*time.Minute)
  if d.Status != tc.want { t.Fatalf("got %s want %s",d.Status,tc.want) }
 }) }
}
