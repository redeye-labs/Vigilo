package main
import("testing";"time";"math")
func TestObservedEnergySeparatesDrivingAndParked(t *testing.T){
 now:=time.Now()
 samples:=func(signal string,values ...float64)[]V2Measurement{
  out:=[]V2Measurement{}
  for i,v:=range values{out=append(out,V2Measurement{VehicleID:"car",Signal:signal,Value:v,Quality:QualityValid,ReceivedAt:now.Add(time.Duration(i)*5*time.Minute)})}
  return out
 }
 r:=ObservedEnergy("car",samples("LifetimeEnergyUsed",100,101,102,102.2),samples("Odometer",10,14,14,14),samples("Gear",1,1,0,0),samples("SentryMode",0,0,1,1))
 if r.DrivingKm==nil||math.Abs(*r.DrivingKm-6.437376)>0.001||r.WhPerKm==nil{t.Fatalf("driving %+v",r)}
 if r.ParkedKWh==nil||r.SentryKWh==nil||math.Abs(*r.ParkedKWh-0.2)>0.001||math.Abs(*r.SentryKWh-0.2)>0.001{t.Fatalf("parked %+v",r)}
 missing:=ObservedEnergy("car",samples("LifetimeEnergyUsed",100,101),samples("Odometer",10,14),nil,nil)
 if missing.DrivingKWh!=nil||missing.ParkedKWh!=nil{t.Fatal("classified without evidence")}
}
