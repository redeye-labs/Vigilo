package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type fakeFleet struct {
	online bool
	paths  []string
}

func (f *fakeFleet) RoundTrip(r *http.Request) (*http.Response, error) {
	f.paths = append(f.paths, r.URL.RequestURI())
	var body string
	switch {
	case strings.HasSuffix(r.URL.Path, "/vehicle_data"):
		body = `{"response":{"charge_state":{"battery_level":80,"battery_range":200},
			"climate_state":{"inside_temp":21.5,"outside_temp":7.25},
			"drive_state":{"shift_state":null},
			"vehicle_state":{"locked":true,"sentry_mode":true,"odometer":10000,"rt":1,"ft":0,"df":0}}}`
	default:
		st := "asleep"
		if f.online {
			st = "online"
		}
		body = `{"response":{"state":"` + st + `"}}`
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: http.Header{}, Request: r}, nil
}

func newFakeTesla(t *testing.T, f *fakeFleet) *Tesla {
	t.Helper()
	st, err := NewStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	_ = st.Update(func(s *State) {
		s.AccessToken, s.AccessExp, s.FleetHost = "x", time.Now().Unix()+3600, "fleet.example"
	})
	ts, err := NewTesla(st, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ts.http = &http.Client{Transport: f}
	return ts
}

// Temperature, contachilometri e bagagliaio/frunk arrivano nella stessa (unica) lettura vehicle_data.
func TestVehicleDataClimateOdometer(t *testing.T) {
	f := &fakeFleet{online: true}
	ts := newFakeTesla(t, f)
	var v VehicleState
	if err := ts.VehicleData(context.Background(), "VIN", &v); err != nil {
		t.Fatal(err)
	}
	if len(f.paths) != 2 || !strings.Contains(f.paths[1], "climate_state") {
		t.Fatalf("chiamate inattese: %v", f.paths)
	}
	if v.InsideTemp == nil || *v.InsideTemp != 21.5 || v.OutsideTemp == nil || *v.OutsideTemp != 7.25 {
		t.Fatalf("temperature: %+v %+v", v.InsideTemp, v.OutsideTemp)
	}
	if v.OdometerKm == nil || int(*v.OdometerKm) != 16093 {
		t.Fatalf("km: %v", v.OdometerKm)
	}
	if v.TrunkOpen == nil || !*v.TrunkOpen || v.FrunkOpen == nil || *v.FrunkOpen {
		t.Fatalf("bagagliaio/frunk: %v %v", v.TrunkOpen, v.FrunkOpen)
	}
}

// Auto addormentata: nessuna lettura (non la svegliamo) e restano gli ultimi valori noti.
func TestVehicleDataOfflineKeepsValues(t *testing.T) {
	f := &fakeFleet{online: false}
	ts := newFakeTesla(t, f)
	in, km := 20.0, 1234.0
	v := VehicleState{InsideTemp: &in, OdometerKm: &km}
	if err := ts.VehicleData(context.Background(), "VIN", &v); err != nil {
		t.Fatal(err)
	}
	if len(f.paths) != 1 || v.InsideTemp == nil || *v.InsideTemp != 20 || v.OdometerKm == nil || *v.OdometerKm != 1234 {
		t.Fatalf("paths=%v v=%+v", f.paths, v)
	}
}

// Un state.json vecchio (senza i nuovi campi) si carica senza errori.
func TestOldStateJSONCompatible(t *testing.T) {
	dir := t.TempDir()
	old := map[string]any{"vin": "", "vehicle": map[string]any{"sentry": "Armed", "battery": 50, "range_km": 300}}
	b, _ := json.Marshal(old)
	if err := os.WriteFile(filepath.Join(dir, "state.json"), b, 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	v := st.Get().Vehicle
	if v.Sentry != "Armed" || v.InsideTemp != nil || v.OdometerKm != nil || v.TrunkOpen != nil {
		t.Fatalf("%+v", v)
	}
}
