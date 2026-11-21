package main

import (
    "testing"
    "time"
)

// The server must not repeat a completed schedule after an HA/Docker restart.
func TestScheduleRunsPersistAcrossRestart(t *testing.T) {
    dir := t.TempDir()
    store, err := NewStore(dir)
    if err != nil { t.Fatal(err) }
    key := "42@2026-11-21T22:00"
    recorded := time.Date(2026, 11, 21, 22, 0, 0, 0, time.UTC)
    if err := store.Update(func(s *State) {
        s.ScheduleRuns = map[string]schedRun{
            key: {Done: true, Attempts: 1, Last: recorded},
        }
    }); err != nil { t.Fatal(err) }

    restored, err := NewStore(dir)
    if err != nil { t.Fatal(err) }
    got := restored.Get().ScheduleRuns[key]
    if !got.Done || got.Attempts != 1 || !got.Last.Equal(recorded) {
        t.Fatalf("schedule run lost after restart: %+v", got)
    }

    // A copy returned to API callers must not mutate the stored execution state.
    snapshot := restored.Get()
    delete(snapshot.ScheduleRuns, key)
    if _, exists := restored.Get().ScheduleRuns[key]; !exists {
        t.Fatal("Store.Get returned an aliased schedule_runs map")
    }
}
