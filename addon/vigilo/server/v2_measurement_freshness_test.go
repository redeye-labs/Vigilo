package main

import (
 "testing"
 "time"
)

func TestV2LatestMarksOldTeslaReadingsAsStale(t *testing.T) {
 metrics,err:=OpenV2Metrics(t.TempDir());if err!=nil{t.Fatal(err)}
 now:=time.Now().UTC()
 old:=insightSample("a","Soc",66,now.Add(-time.Hour))
 recent:=insightSample("a","RatedRange",278,now)
 if _,err=metrics.Add(old);err!=nil{t.Fatal(err)}
 if _,err=metrics.Add(recent);err!=nil{t.Fatal(err)}
 got:=metrics.Latest("a",[]string{"Soc","RatedRange"})
 if len(got)!=2{t.Fatalf("missing measures: %+v",got)}
 for _,m:=range got{
  if m.Signal=="Soc" && m.Quality!=QualityStale{t.Fatal("old SOC shown as current")}
  if m.Signal=="RatedRange" && m.Quality!=QualityValid{t.Fatal("new range shown as stale")}
 }
 reread:=metrics.History("a","Soc",time.Time{},5)
 if len(reread)!=1 || reread[0].Quality!=QualityValid{
  t.Fatal("read-time staleness must not overwrite original measurement provenance")
 }
 battery:=BatteryObservations("a",metrics.Latest("a",[]string{"Soc"}))
 if !battery.Stale || battery.LatestReceivedAt.IsZero(){t.Fatal("battery summary lost stale flag or timestamp")}
}
