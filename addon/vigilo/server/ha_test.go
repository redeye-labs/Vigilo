package main

import (
	"os"
	"path/filepath"
	"testing"
)

// Con s6-overlay il token non è nell'ambiente ma in /run/s6/container_environment.
func TestSupervisorTokenFromS6(t *testing.T) {
	t.Setenv("SUPERVISOR_TOKEN", "")
	t.Setenv("HASSIO_TOKEN", "")
	dir := t.TempDir()
	old := supervisorEnvDirs
	supervisorEnvDirs = []string{filepath.Join(dir, "missing"), dir}
	defer func() { supervisorEnvDirs = old }()

	if tok, _ := supervisorToken(); tok != "" {
		t.Fatalf("atteso nessun token")
	}
	if err := os.WriteFile(filepath.Join(dir, "SUPERVISOR_TOKEN"), []byte("abc\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	tok, src := supervisorToken()
	if tok != "abc" || src == "" {
		t.Fatalf("token=%q src=%q", tok, src)
	}
	t.Setenv("SUPERVISOR_TOKEN", "env")
	if tok, _ := supervisorToken(); tok != "env" {
		t.Fatalf("l'ambiente deve avere la precedenza, got %q", tok)
	}
}

func TestManualMQTT(t *testing.T) {
	if (Options{}).manualMQTT() != nil {
		t.Fatal("senza host deve usare il Supervisor")
	}
	m := Options{MQTTHost: "core-mosquitto", MQTTUsername: "u"}.manualMQTT()
	if m.Port != 1883 || m.Host != "core-mosquitto" {
		t.Fatalf("%+v", m)
	}
	if (Options{MQTTHost: "h", MQTTSSL: true}).manualMQTT().Port != 8883 {
		t.Fatal("porta SSL")
	}
}
