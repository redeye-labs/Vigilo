package main

import (
 "encoding/json"
 "errors"
 "fmt"
 "os"
 "path/filepath"
 "sort"
 "sync"
 "time"
)

// V2Archive stores non-secret history independently of state.json and events.json.
// It is a transitional store: a future database migration can consume its schema.
// No authentication tokens, credentials, vehicle locations or VINs are saved here.
type V2Archive struct {
 mu sync.Mutex
 path string
 data V2ArchiveData
}

type V2ArchiveData struct {
 Schema int `json:"schema"`
 LegacyVehicleID VehicleID `json:"legacy_vehicle_id,omitempty"`
 Events []V2ArchivedEvent `json:"events"`
}

type V2ArchivedEvent struct {
 ID int64 `json:"id"`
 VehicleID VehicleID `json:"vehicle_id"`
 Timestamp int64 `json:"timestamp"`
 Type string `json:"type"`
 Title string `json:"title"`
 Message string `json:"message"`
}

const v2ArchiveSchema = 1

func OpenV2Archive(dir string) (*V2Archive, error) {
 if err := os.MkdirAll(dir, 0700); err != nil { return nil, err }
 a := &V2Archive{path: filepath.Join(dir, "v2-history.json"), data: V2ArchiveData{Schema: v2ArchiveSchema, Events: []V2ArchivedEvent{}}}
 b, err := os.ReadFile(a.path)
 if errors.Is(err, os.ErrNotExist) { return a, nil }
 if err != nil { return nil, err }
 if err := json.Unmarshal(b, &a.data); err != nil { return nil, fmt.Errorf("archivio Vigilo 2 non valido: %w", err) }
 if a.data.Schema != v2ArchiveSchema { return nil, fmt.Errorf("versione archivio Vigilo 2 non supportata: %d", a.data.Schema) }
 return a, nil
}

func (a *V2Archive) saveLocked(next V2ArchiveData) error {
 b, err := json.Marshal(next)
 if err != nil { return err }
 dir := filepath.Dir(a.path)
 f, err := os.CreateTemp(dir, ".v2-history-*.tmp")
 if err != nil { return err }
 tmp := f.Name()
 defer os.Remove(tmp)
 if err = f.Chmod(0600); err != nil { f.Close(); return err }
 if _, err = f.Write(b); err != nil { f.Close(); return err }
 if err = f.Sync(); err != nil { f.Close(); return err }
 if err = f.Close(); err != nil { return err }
 if err = os.Rename(tmp, a.path); err != nil { return err }
 a.data = next
 return nil
}

func (a *V2Archive) ImportLegacyEvents(vehicleID VehicleID, legacy []Event) (int, error) {
 if vehicleID == "" { return 0, errors.New("vehicle_id obbligatorio") }
 a.mu.Lock()
 defer a.mu.Unlock()
 // Legacy events have no vehicle identifier; never reassign them to a
 // different vehicle after the first successful migration.
 if a.data.LegacyVehicleID!="" && a.data.LegacyVehicleID!=vehicleID {return 0,nil}
 existing := make(map[int64]bool, len(a.data.Events))
 for _, e := range a.data.Events {
  if e.VehicleID == vehicleID { existing[e.ID] = true }
 }
 next := V2ArchiveData{Schema: v2ArchiveSchema, LegacyVehicleID:a.data.LegacyVehicleID, Events: append([]V2ArchivedEvent(nil), a.data.Events...)}
 added := 0
 for _, e := range legacy {
  if e.ID <= 0 || e.TS <= 0 || existing[e.ID] { continue }
  next.Events = append(next.Events, V2ArchivedEvent{
   ID: e.ID, VehicleID: vehicleID, Timestamp: e.TS,
   Type: e.Type, Title: e.Title, Message: e.Message,
  })
  existing[e.ID] = true
  added++
 }
 if added == 0 { return 0, nil }
 if next.LegacyVehicleID==""{next.LegacyVehicleID=vehicleID}
 sort.SliceStable(next.Events, func(i, j int) bool {
  if next.Events[i].Timestamp == next.Events[j].Timestamp {
   if next.Events[i].VehicleID == next.Events[j].VehicleID { return next.Events[i].ID < next.Events[j].ID }
   return next.Events[i].VehicleID < next.Events[j].VehicleID
  }
  return next.Events[i].Timestamp < next.Events[j].Timestamp
 })
 if err := a.saveLocked(next); err != nil { return 0, err }
 return added, nil
}

// Snapshot returns a defensive copy, optionally restricted to one vehicle.
// It never returns the secret-bearing legacy State.
func (a *V2Archive) Snapshot(vehicleID VehicleID, limit int) []V2ArchivedEvent {
 a.mu.Lock()
 defer a.mu.Unlock()
 if limit <= 0 || limit > 10000 { limit = 1000 }
 out := make([]V2ArchivedEvent, 0)
 for i := len(a.data.Events)-1; i >= 0 && len(out) < limit; i-- {
  e := a.data.Events[i]
  if vehicleID != "" && e.VehicleID != vehicleID { continue }
  out = append(out, e)
 }
 return out
}

type V2IncidentSummary struct {
 VehicleID VehicleID `json:"vehicle_id"`
 StartedAt time.Time `json:"started_at"`
 EndedAt time.Time `json:"ended_at"`
 EventCount int `json:"event_count"`
 Panic bool `json:"panic"`
}

func (a *V2Archive) SecurityIncidents(vehicleID VehicleID, window time.Duration, limit int) []V2IncidentSummary {
 // Avoid calling the grouping algorithm while holding the archive lock.
 records := a.Snapshot(vehicleID, limit)
 input := make([]V2SecurityEvent, 0, len(records))
 for _, e := range records {
  input = append(input, V2SecurityEvent{ID: e.ID, VehicleID: e.VehicleID, Type: e.Type, Time: time.UnixMilli(e.Timestamp)})
 }
 groups := GroupV2SecurityEvents(input, window)
 out := make([]V2IncidentSummary, 0, len(groups))
 for _, g := range groups {
  out = append(out, V2IncidentSummary{
   VehicleID: g.VehicleID, StartedAt: g.StartedAt, EndedAt: g.EndedAt,
   EventCount: len(g.EventIDs), Panic: g.HasPanic,
  })
 }
 return out
}
