package main

import (
 "testing"
 "time"
)

func TestV2RuleEngineOptInEdgesCooldownAndPersistence(t *testing.T){
 dir:=t.TempDir()
 engine,err:=OpenV2RuleEngine(dir);if err!=nil{t.Fatal(err)}
 now:=time.Date(2026,11,26,20,0,0,0,time.UTC)
 if len(engine.Evaluate("a","soc_below",10,now))!=0{t.Fatal("unconfigured rule fired")}
 rule:=V2AlertRule{ID:"low-battery",Condition:"soc_below",VehicleID:"ignored",Threshold:20,CooldownSec:300,Enabled:true}
 if err=engine.Put("a",rule);err!=nil{t.Fatal(err)}
 if len(engine.Evaluate("b","soc_below",10,now))!=0{t.Fatal("cross-car trigger")}
 fired:=engine.Evaluate("a","soc_below",19,now)
 if len(fired)!=1{t.Fatalf("first edge missed %v",fired)}
 if len(engine.Evaluate("a","soc_below",18,now.Add(time.Minute)))!=0{t.Fatal("spammed below threshold")}
 if len(engine.Evaluate("a","soc_below",22,now.Add(2*time.Minute)))!=0{t.Fatal("hysteresis reset notified")}
 if len(engine.Evaluate("a","soc_below",17,now.Add(3*time.Minute)))!=0{t.Fatal("cooldown bypassed")}
 if len(engine.Evaluate("a","soc_below",17,now.Add(7*time.Minute)))!=0{t.Fatal("same falling edge re-notified")}
 if len(engine.Evaluate("a","soc_below",22,now.Add(8*time.Minute)))!=0{t.Fatal("high SOC incorrectly notified")}
 if len(engine.Evaluate("a","soc_below",18,now.Add(10*time.Minute)))!=1{t.Fatal("new edge not notified")}
 recovered,err:=OpenV2RuleEngine(dir);if err!=nil{t.Fatal(err)}
 if len(recovered.Rules("a"))!=1||len(recovered.Rules("b"))!=0{t.Fatal("rule persistence or vehicle isolation failed")}
}

func TestV2RuleEngineRejectsDangerousActions(t *testing.T){
 engine,err:=OpenV2RuleEngine(t.TempDir());if err!=nil{t.Fatal(err)}
 unsafe:=V2AlertRule{ID:"remote-drive",Condition:"remote_start_drive",CooldownSec:300,Enabled:true}
 if err=engine.Put("a",unsafe);err==nil{t.Fatal("unsafe Tesla operation accepted")}
 bad:=V2AlertRule{ID:"battery",Condition:"soc_below",Threshold:99,CooldownSec:300,Enabled:true}
 if err=engine.Put("a",bad);err==nil{t.Fatal("unbounded threshold accepted")}
}
