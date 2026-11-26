package main

import (
 "os"
 "path/filepath"
 "testing"
 "time"
)

func TestV2ArchiveLegacyMigrationIsIdempotentAndIsolated(t *testing.T) {
 dir := t.TempDir()
 archive, err := OpenV2Archive(dir)
 if err != nil { t.Fatal(err) }
 ts := time.Date(2026, 11, 26, 21, 0, 0, 0, time.UTC).UnixMilli()
 legacy := []Event{
  {ID: 1, TS: ts, Type: EvAware, Title: "Aware", Message: "a"},
  {ID: 2, TS: ts+60_000, Type: EvPanic, Title: "Panic", Message: "b"},
 }
 n, err := archive.ImportLegacyEvents("car-1", legacy)
 if err != nil || n != 2 { t.Fatalf("first import n=%d err=%v", n, err) }
 n, err = archive.ImportLegacyEvents("car-1", legacy)
 if err != nil || n != 0 { t.Fatalf("duplicate import n=%d err=%v", n, err) }
 n, err = archive.ImportLegacyEvents("car-2", legacy[:1])
 if err != nil || n != 1 { t.Fatalf("other vehicle n=%d err=%v", n, err) }
 if got := len(archive.Snapshot("car-1", 100)); got != 2 { t.Fatalf("vehicle 1 got %d", got) }
 if got := len(archive.Snapshot("car-2", 100)); got != 1 { t.Fatalf("vehicle 2 got %d", got) }
 archive2, err := OpenV2Archive(dir)
 if err != nil { t.Fatal(err) }
 if len(archive2.Snapshot("", 100)) != 3 { t.Fatal("archive not persistent") }
 if fi, err := os.Stat(filepath.Join(dir, "v2-history.json")); err != nil || fi.Mode().Perm() != 0600 {
  t.Fatalf("archive must have mode 0600: %v %v", fi, err)
 }
}

func TestV2ArchiveDoesNotModifyOriginalEvents(t *testing.T) {
 dir := t.TempDir()
 original := []byte("original legacy history")
 file := filepath.Join(dir, "events.json")
 if err := os.WriteFile(file, original, 0600); err != nil { t.Fatal(err) }
 a, err := OpenV2Archive(dir)
 if err != nil { t.Fatal(err) }
 _, err = a.ImportLegacyEvents("car-1", []Event{{ID: 1, TS: 12345, Type: EvAware}})
 if err != nil { t.Fatal(err) }
 b, err := os.ReadFile(file)
 if err != nil || string(b) != string(original) { t.Fatal("modified legacy event history") }
}

func TestV2ArchiveIncidentSummary(t *testing.T) {
 a, err := OpenV2Archive(t.TempDir())
 if err != nil { t.Fatal(err) }
 start := time.Now().Add(-time.Hour).UnixMilli()
 _, err = a.ImportLegacyEvents("car-1", []Event{
  {ID: 1, TS: start, Type: EvAware},
  {ID: 2, TS: start+60_000, Type: EvPanic},
  {ID: 3, TS: start+180_000, Type: EvCommand},
 })
 if err != nil { t.Fatal(err) }
 groups := a.SecurityIncidents("car-1", 5*time.Minute, 50)
 if len(groups) != 1 || groups[0].EventCount != 2 || !groups[0].Panic {
  t.Fatalf("unexpected incident summary %+v", groups)
 }
}

func TestV2ArchiveRejectsUnknownSchemaWithoutOverwriting(t *testing.T) {
 dir := t.TempDir()
 path := filepath.Join(dir, "v2-history.json")
 src := []byte(`{"schema":999,"events":[]}`)
 if err := os.WriteFile(path, src, 0600); err != nil { t.Fatal(err) }
 if _, err := OpenV2Archive(dir); err == nil { t.Fatal("future schema accepted") }
 got, err := os.ReadFile(path)
 if err != nil || string(got) != string(src) { t.Fatal("future schema corrupted") }
}
