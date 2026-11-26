package main

import (
 "encoding/csv"
 "errors"
 "io"
 "strconv"
 "time"
)

// V2SecurityReport is limited to archived observations. It cannot claim
// coverage for time periods before Vigilo started storing events.
type V2SecurityReport struct {
 VehicleID VehicleID `json:"vehicle_id"`
 From time.Time `json:"from"`
 To time.Time `json:"to"`
 TotalEvents int `json:"total_events"`
 AwareEvents int `json:"aware_events"`
 PanicEvents int `json:"panic_events"`
 GroupedIncidents int `json:"grouped_incidents"`
 Coverage string `json:"coverage"`
}

// SecurityReport is explicitly partial because legacy history is capped.
// Window boundaries are inclusive-from, exclusive-to.
func (a *V2Archive) SecurityReport(vehicleID VehicleID, from, to time.Time) (V2SecurityReport,error) {
 if vehicleID=="" || from.IsZero() || to.IsZero() || !to.After(from) {
  return V2SecurityReport{},errors.New("intervallo report non valido")
 }
 r:=V2SecurityReport{VehicleID:vehicleID,From:from,To:to,Coverage:"partial_history_only"}
 events:=a.Snapshot(vehicleID,10000)
 candidates:=make([]V2SecurityEvent,0)
 for _,e:=range events {
  ts:=time.UnixMilli(e.Timestamp)
  if ts.Before(from)||!ts.Before(to){continue}
  r.TotalEvents++
  if e.Type==EvAware||e.Type==EvPanic{
   candidates=append(candidates,V2SecurityEvent{ID:e.ID,VehicleID:e.VehicleID,Time:ts,Type:e.Type})
  }
  if e.Type==EvAware{r.AwareEvents++}
  if e.Type==EvPanic{r.PanicEvents++}
 }
 r.GroupedIncidents=len(GroupV2SecurityEvents(candidates,5*time.Minute))
 return r,nil
}

// ExportSecurityCSV only exports a strict, allowlisted set of fields.
// Raw titles and messages are intentionally omitted: they may contain
// location details or user-provided text, including sensitive information.
func (a *V2Archive) ExportSecurityCSV(w io.Writer,vehicleID VehicleID) error {
 if vehicleID==""{return errors.New("vehicle_id obbligatorio")}
 cw:=csv.NewWriter(w)
 if err:=cw.Write([]string{"vehicle_id","event_id","timestamp_utc","type"});err!=nil{return err}
 for _,e:=range a.Snapshot(vehicleID,10000){
  ts:=time.UnixMilli(e.Timestamp).UTC().Format(time.RFC3339)
  if err:=cw.Write([]string{string(e.VehicleID),strconv.FormatInt(e.ID,10),ts,e.Type});err!=nil{return err}
 }
 cw.Flush()
 return cw.Error()
}
