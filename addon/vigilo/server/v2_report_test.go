package main

import (
 "bytes"
 "encoding/csv"
 "strings"
 "testing"
 "time"
)

func TestV2ReportPartialCoverageAndVehicleIsolation(t *testing.T){
 a,err:=OpenV2Archive(t.TempDir());if err!=nil{t.Fatal(err)}
 start:=time.Date(2026,11,26,20,0,0,0,time.UTC)
 rows:=[]Event{
  {ID:1,TS:start.UnixMilli(),Type:EvAware},
  {ID:2,TS:start.Add(time.Minute).UnixMilli(),Type:EvPanic},
  {ID:3,TS:start.Add(20*time.Minute).UnixMilli(),Type:EvCommand},
 }
 if _,err=a.ImportLegacyEvents("a",rows);err!=nil{t.Fatal(err)}
 if _,err=a.ImportLegacyEvents("b",rows[:1]);err!=nil{t.Fatal(err)}
 report,err:=a.SecurityReport("a",start.Add(-time.Minute),start.Add(time.Hour))
 if err!=nil{t.Fatal(err)}
 if report.TotalEvents!=3||report.AwareEvents!=1||report.PanicEvents!=1||
    report.GroupedIncidents!=1||report.Coverage!="partial_history_only"{
  t.Fatalf("incorrect report: %+v",report)
 }
 if _,err=a.SecurityReport("a",start,start);err==nil{t.Fatal("bad time range accepted")}
}

func TestV2CSVExportRedactsMessageAndOtherVehicles(t *testing.T){
 a,err:=OpenV2Archive(t.TempDir());if err!=nil{t.Fatal(err)}
 now:=time.Now().UnixMilli()
 if _,err=a.ImportLegacyEvents("a",[]Event{{ID:1,TS:now,Type:EvAware,Title:"private",Message:"access_token=TOP_SECRET"}});err!=nil{t.Fatal(err)}
 if _,err=a.ImportLegacyEvents("b",[]Event{{ID:1,TS:now,Type:EvPanic,Message:"OTHER_CAR"}});err!=nil{t.Fatal(err)}
 var out bytes.Buffer
 if err=a.ExportSecurityCSV(&out,"a");err!=nil{t.Fatal(err)}
 if strings.Contains(out.String(),"TOP_SECRET")||strings.Contains(out.String(),"OTHER_CAR")||strings.Contains(out.String(),"private"){
  t.Fatal("sensitive text leaked into export")
 }
 rows,err:=csv.NewReader(strings.NewReader(out.String())).ReadAll()
 if err!=nil||len(rows)!=2||len(rows[1])!=4||rows[1][0]!="a"{t.Fatalf("unexpected export %q err=%v",out.String(),err)}
}
