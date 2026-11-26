package main

import (
 "encoding/json"
 "os"
 "path/filepath"
 "testing"
 "time"
)

func TestV2MetricJournalAppendAndRecover(t *testing.T){
 dir:=t.TempDir()
 metrics,err:=OpenV2Metrics(dir);if err!=nil{t.Fatal(err)}
 at:=time.Date(2026,11,26,20,0,0,0,time.UTC)
 if _,err=metrics.Add(insightSample("a","RatedRange",278,at));err!=nil{t.Fatal(err)}
 if _,err=metrics.Add(insightSample("a","RatedRange",270,at.Add(time.Minute)));err!=nil{t.Fatal(err)}
 file:=filepath.Join(dir,"v2-measurements.ndjson")
 info,err:=os.Stat(file)
 if err!=nil||info.Mode().Perm()!=0600{t.Fatalf("journal mode %v %v",info,err)}
 reopened,err:=OpenV2Metrics(dir)
 if err!=nil{t.Fatal(err)}
 if len(reopened.History("a","RatedRange",time.Time{},10))!=2{t.Fatal("journal not replayed")}
}

func TestV2MetricJournalIgnoresCommittedSnapshotRecords(t *testing.T){
 dir:=t.TempDir()
 at:=time.Date(2026,11,26,20,0,0,0,time.UTC)
 first:=insightSample("a","RatedRange",278,at)
 second:=insightSample("a","RatedRange",270,at.Add(time.Minute))
 snap,_:=json.Marshal(v2MetricsData{Schema:1,LastSeq:1,Entries:[]V2Measurement{first}})
 if err:=os.WriteFile(filepath.Join(dir,"v2-measurements.json"),snap,0600);err!=nil{t.Fatal(err)}
 old,_:=json.Marshal(v2MetricLogRecord{Seq:1,Reading:first})
 newer,_:=json.Marshal(v2MetricLogRecord{Seq:2,Reading:second})
 if err:=os.WriteFile(filepath.Join(dir,"v2-measurements.ndjson"),append(append(old,'\n'),append(newer,'\n')...),0600);err!=nil{t.Fatal(err)}
 restored,err:=OpenV2Metrics(dir)
 if err!=nil{t.Fatal(err)}
 if got:=restored.History("a","RatedRange",time.Time{},10);len(got)!=2 || got[1].Value!=270{
  t.Fatalf("journal duplicate or lost update: %+v",got)
 }
}

func TestV2MetricJournalRejectsCorruptTail(t *testing.T){
 dir:=t.TempDir()
 file:=filepath.Join(dir,"v2-measurements.ndjson")
 if err:=os.WriteFile(file,[]byte("broken-json\n"),0600);err!=nil{t.Fatal(err)}
 if _,err:=OpenV2Metrics(dir);err==nil{t.Fatal("corruption silently accepted")}
 content,err:=os.ReadFile(file)
 if err!=nil || string(content)!="broken-json\n"{t.Fatal("corrupt journal must not be overwritten")}
}
