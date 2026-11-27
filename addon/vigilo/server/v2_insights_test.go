package main

import (
 "math"
 "testing"
 "time"
)

func insightSample(vehicle VehicleID,signal string,value float64,ts time.Time) V2Measurement{
 return V2Measurement{VehicleID:vehicle,Signal:signal,Value:value,Unit:"kWh",Source:SourceFleetTelemetry,Quality:QualityValid,ReceivedAt:ts,MeasuredAt:ts}
}

func TestV2ChargingObservationsNeverGuessMissingEnergy(t *testing.T){
 start:=time.Date(2026,11,26,10,0,0,0,time.UTC)
 rate:=0.30
 samples:=[]V2Measurement{
  insightSample("a","DCChargingEnergyIn",2,start),
  insightSample("a","DCChargingEnergyIn",7,start.Add(time.Hour)),
  insightSample("b","DCChargingEnergyIn",120,start.Add(time.Hour)),
  insightSample("a","DCChargingEnergyIn",0,start.Add(9*time.Hour)),
  insightSample("a","DCChargingEnergyIn",4,start.Add(10*time.Hour)),
 }
 r:=ChargingSessions("a",samples,&rate)
 if len(r)!=2{t.Fatalf("got %d sessions: %+v",len(r),r)}
 if r[0].BatteryEnergyKWh!=7 || r[0].EstimatedCostEUR==nil || math.Abs(*r[0].EstimatedCostEUR-2.1)>0.001{
  t.Fatalf("wrong first session: %+v",r[0])
 }
 if r[1].BatteryEnergyKWh!=4{t.Fatalf("wrong second: %+v",r[1])}
 if len(ChargingSessions("c",samples,nil))!=0{t.Fatal("cross-vehicle data leak")}
}

func TestV2OdometerSegmentsUseRealMiles(t *testing.T){
 start:=time.Date(2026,11,26,10,0,0,0,time.UTC)
 ss:=[]V2Measurement{
  insightSample("a","Odometer",1000,start),
  insightSample("a","Odometer",1005,start.Add(10*time.Minute)),
  insightSample("a","Odometer",1006,start.Add(20*time.Minute)),
  insightSample("a","Odometer",1006,start.Add(30*time.Minute)),
  insightSample("a","Odometer",1012,start.Add(time.Hour)),
 }
 r:=TripObservations("a",ss)
 if len(r)!=2{t.Fatalf("expected two observed movement segments: %+v",r)}
 if math.Abs(r[0].DistanceKm-9.66)>0.02 || math.Abs(r[1].DistanceKm-9.66)>0.02 {
  t.Fatalf("incorrect conversion %+v",r)
 }
 if len(TripObservations("b",ss))!=0{t.Fatal("vehicle leak")}
}

func TestV2BatteryWithoutValidatedBaselineHasNoSOH(t *testing.T){
 now:=time.Now()
 samples:=[]V2Measurement{
  insightSample("a","NominalFullPackEnergyKwh",52.3,now),
  {VehicleID:"a",Signal:"RatedRange",Value:163,Unit:"mile",Source:SourceFleetTelemetry,Quality:QualityValid,ReceivedAt:now,MeasuredAt:now},
  insightSample("a","Soc",66,now),
 }
 r:=BatteryObservations("a",samples)
 if r.NominalFullPackKWh==nil||*r.NominalFullPackKWh!=52.3||r.HealthPercent!=nil{t.Fatalf("invented health: %+v",r)}
 if r.RatedRangeKm==nil||math.Abs(*r.RatedRangeKm-163*1.609344)>0.001{t.Fatalf("wrong range %+v",r)}
}

func TestLegacyRatedRangeMislabeledMiles(t *testing.T){
 now:=time.Now()
 for _,unit:=range []string{"km","mile"}{
  m:=V2Measurement{VehicleID:"a",Signal:"RatedRange",Value:163,Unit:unit,Source:SourceFleetTelemetry,Quality:QualityValid,ReceivedAt:now,MeasuredAt:now}
  r:=BatteryObservations("a",[]V2Measurement{m})
  if r.RatedRangeKm==nil||math.Abs(*r.RatedRangeKm-262.323072)>0.001{t.Fatalf("range conversion failed: %+v",r)}
 }
}
