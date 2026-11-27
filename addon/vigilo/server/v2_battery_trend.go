package main

import (
	"math"
	"sort"
	"time"
)

type V2BatteryTrend struct {
	From          time.Time `json:"from"`
	To            time.Time `json:"to"`
	FirstBMSKWh   float64   `json:"first_bms_kwh"`
	LatestBMSKWh  float64   `json:"latest_bms_kwh"`
	PercentChange float64   `json:"percent_change"`
	SampleCount   int       `json:"sample_count"`
	Method        string    `json:"method"`
	Note          string    `json:"note"`
}

// BatteryCapacityTrend compares median BMS full-pack measurements recorded at
// least 30 days apart. It is NOT a state-of-health percentage: no factory or
// new-pack reference is available. Rejects short and inconsistent histories.
func BatteryCapacityTrend(vehicle VehicleID, samples []V2Measurement) (V2BatteryTrend, bool) {
	valid := make([]V2Measurement, 0)
	for _, m := range samples {
		if m.VehicleID != vehicle || m.Signal != "NominalFullPackEnergyKwh" ||
			m.Source != SourceFleetTelemetry || !finiteNumber(m.Value) ||
			m.Value < 10 || m.Value > 200 || m.ReceivedAt.IsZero() {
			continue
		}
		valid = append(valid, m)
	}
	if len(valid) < 6 {
		return V2BatteryTrend{}, false
	}
	sort.SliceStable(valid, func(i, j int) bool { return valid[i].ReceivedAt.Before(valid[j].ReceivedAt) })
	if valid[len(valid)-1].ReceivedAt.Sub(valid[0].ReceivedAt) < 30*24*time.Hour {
		return V2BatteryTrend{}, false
	}
	first, okA := medianBMS(valid[:3])
	last, okB := medianBMS(valid[len(valid)-3:])
	if !okA || !okB {
		return V2BatteryTrend{}, false
	}
	// A large unexplained jump indicates a BMS recalibration or invalid
	// measurement; show the underlying readings without a degradation claim.
	change := (last/first - 1) * 100
	if math.Abs(change) > 15 {
		return V2BatteryTrend{}, false
	}
	return V2BatteryTrend{
		From: valid[0].ReceivedAt, To: valid[len(valid)-1].ReceivedAt,
		FirstBMSKWh: first, LatestBMSKWh: last, PercentChange: math.Round(change*100) / 100,
		SampleCount: len(valid), Method: "median first 3 BMS measurements vs median last 3 over at least 30 days",
		Note: "Variazione relativa delle letture BMS nel periodo. Non equivale al degrado totale o a un SoH certificato.",
	}, true
}

func medianBMS(samples []V2Measurement) (float64, bool) {
	if len(samples) != 3 {
		return 0, false
	}
	values := []float64{samples[0].Value, samples[1].Value, samples[2].Value}
	sort.Float64s(values)
	if values[2]-values[0] > values[1]*0.05 {
		return 0, false
	}
	return values[1], true
}

type V2BatteryHistoryPoint struct {
	Week        time.Time `json:"week"`
	CapacityKWh float64   `json:"capacity_kwh"`
	SampleCount int       `json:"sample_count"`
}

// BatteryCapacityHistory summarizes measured BMS capacity by UTC week.
func BatteryCapacityHistory(vehicle VehicleID, samples []V2Measurement) []V2BatteryHistoryPoint {
	byWeek := make(map[time.Time][]float64)
	for _, m := range samples {
		if m.VehicleID != vehicle || m.Signal != "NominalFullPackEnergyKwh" ||
			m.Source != SourceFleetTelemetry || m.Quality != QualityValid ||
			!finiteNumber(m.Value) || m.Value < 10 || m.Value > 200 || m.ReceivedAt.IsZero() {
			continue
		}
		day := m.ReceivedAt.UTC().Truncate(24 * time.Hour)
		week := day.AddDate(0, 0, -((int(day.Weekday()) + 6) % 7))
		byWeek[week] = append(byWeek[week], m.Value)
	}
	points := make([]V2BatteryHistoryPoint, 0, len(byWeek))
	for week, values := range byWeek {
		sort.Float64s(values)
		median := (values[(len(values)-1)/2] + values[len(values)/2]) / 2
		points = append(points, V2BatteryHistoryPoint{Week: week, CapacityKWh: median, SampleCount: len(values)})
	}
	sort.Slice(points, func(i, j int) bool { return points[i].Week.Before(points[j].Week) })
	return points
}
