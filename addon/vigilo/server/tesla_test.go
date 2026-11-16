package main

import (
	"encoding/base64"
	"strings"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	"github.com/teslamotors/vehicle-command/pkg/sign"
)

// La configurazione firmata localmente deve essere un JWS Tesla.SS256 verificabile con la nostra chiave.
func TestTelemetryJWSSignature(t *testing.T) {
	st, err := NewStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ts, err := NewTesla(st, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	jws, err := sign.SignMessageForFleet(ts.key, "TelemetryClient", jwt.MapClaims{"hostname": "vigilo.example.ts.net", "port": 8443, "exp": 2000000000})
	if err != nil {
		t.Fatal(err)
	}
	pub := ts.key.PublicBytes()
	tok, err := jwt.Parse(jws, func(*jwt.Token) (any, error) { return pub, nil })
	if err != nil || !tok.Valid {
		t.Fatalf("JWS non valido: %v", err)
	}
	c := tok.Claims.(jwt.MapClaims)
	if tok.Method.Alg() != "Tesla.SS256" || c["aud"] != "com.tesla.fleet.TelemetryClient" || c["iss"] != base64.StdEncoding.EncodeToString(pub) || c["hostname"] != "vigilo.example.ts.net" {
		t.Fatalf("claims/alg inattesi: %s %v", tok.Method.Alg(), c)
	}
}

func TestSanitizeForLog(t *testing.T) {
	in := `{"error":"not_found","vin":"5YJ3E7EA1KF001234","token":"eyJhbGciOi.eyJzdWIiOi.c2lnbg"}`
	out := sanitizeForLog(in, 300)
	if strings.Contains(out, "5YJ3E7EA1KF") || strings.Contains(out, "eyJ") || !strings.Contains(out, "***1234") || !strings.Contains(out, "not_found") {
		t.Fatalf("sanitize: %s", out)
	}
	if maskVINs("/api/1/vehicles/5YJ3E7EA1KF001234/fleet_telemetry_config") != "/api/1/vehicles/***1234/fleet_telemetry_config" {
		t.Fatal("maskVINs")
	}
}
