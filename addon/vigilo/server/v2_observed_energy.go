package main

import (
 "sort"
 "time"
)

type V2ObservedEnergy struct {
 DrivingKWh *float64 `json:"driving_kwh,omitempty"`
 DrivingKm *float64 `json:"driving_km,omitempty"`
 WhPerKm *float64 `json:"wh_per_km,omitempty"`
 ParkedKWh *float64 `json:"parked_kwh,omitempty"`
 SentryKWh *float64 `json:"sentry_kwh,omitempty"`
 ParkedHours *float64 `json:"parked_hours,omitempty"`
 SentryHours *float64 `json:"sentry_hours,omitempty"`
 Note string `json:"note"`
}

// ObservedEnergy classifies only short, fully bounded intervals with fresh
// state and odometer evidence. Sentry energy is total vehicle use during
// Sentry-active parking, not an isolated measurement of the cameras.
func ObservedEnergy(vehicle VehicleID, energy, odo, gear, sentry []V2Measurement) V2ObservedEnergy {
 r:=V2ObservedEnergy{Note:"Intervalli osservati (max 10 min), non copertura completa. Energia Sentinella = consumo totale durante soste con Sentinella attiva; incluso nel totale da fermo."}
 clean:=func(in []V2Measurement, signal string)[]V2Measurement{
  out:=[]V2Measurement{}
  for _,m:=range in{if m.VehicleID==vehicle&&m.Signal==signal&&m.Quality==QualityValid&&finiteNumber(m.Value)&&m.Value>=0&&!m.ReceivedAt.IsZero(){out=append(out,m)}}
  sort.SliceStable(out,func(i,j int)bool{return out[i].ReceivedAt.Before(out[j].ReceivedAt)})
  return out
 }
 energy=clean(energy,"LifetimeEnergyUsed");odo=clean(odo,"Odometer");gear=clean(gear,"Gear");sentry=clean(sentry,"SentryMode")
 // Endpoint samples must precede the energy timestamp by at most 90 seconds.
 at:=func(xs []V2Measurement,t time.Time)(float64,bool){
  i:=sort.Search(len(xs),func(i int)bool{return xs[i].ReceivedAt.After(t)})-1
  if i<0||t.Sub(xs[i].ReceivedAt)>90*time.Second{return 0,false}
  return xs[i].Value,true
 }
 stable:=func(xs []V2Measurement,a,b time.Time,want float64)bool{
  x,ok:=at(xs,a);y,ok2:=at(xs,b)
  if !ok||!ok2||x!=want||y!=want{return false}
  i:=sort.Search(len(xs),func(i int)bool{return xs[i].ReceivedAt.After(a)})
  for ;i<len(xs)&&!xs[i].ReceivedAt.After(b);i++{if xs[i].Value!=want{return false}}
  return true
 }
 add:=func(p **float64,x float64){if *p==nil{v:=0.0;*p=&v};**p+=x}
 for i:=1;i<len(energy);i++{
  a,b:=energy[i-1],energy[i];dt:=b.ReceivedAt.Sub(a.ReceivedAt);de:=b.Value-a.Value
  if dt<=0||dt>10*time.Minute||de<0||de>25{continue}
  x,ok:=at(odo,a.ReceivedAt);y,ok2:=at(odo,b.ReceivedAt)
  if !ok||!ok2||y<x{continue}
  km:=(y-x)*1.609344
  if km>0.1&&km<=50&&stable(gear,a.ReceivedAt,b.ReceivedAt,1){
   add(&r.DrivingKWh,de);add(&r.DrivingKm,km)
  }else if km<0.01&&stable(gear,a.ReceivedAt,b.ReceivedAt,0){
   add(&r.ParkedKWh,de);add(&r.ParkedHours,dt.Hours())
   if stable(sentry,a.ReceivedAt,b.ReceivedAt,1){add(&r.SentryKWh,de);add(&r.SentryHours,dt.Hours())}
  }
 }
 if r.DrivingKm!=nil&&*r.DrivingKm>=5&&r.DrivingKWh!=nil&&*r.DrivingKWh>0{v:=*r.DrivingKWh*1000 / *r.DrivingKm;r.WhPerKm=&v}
 return r
}
