package main

// Prova completa della modalità standalone con i veri binari (facoltativa, gira nel container di prova):
//   VIGILO_MOSQUITTO=/usr/sbin/mosquitto VIGILO_FT_BIN=/usr/bin/fleet-telemetry go test -run StandaloneE2E -v
// broker interno -> Bus -> fleet-telemetry in TLS con la CA di Vigilo -> dati simulati sul topic di
// fleet-telemetry -> evento Aware nello storico, API per l'app e notifica ntfy.
import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

func TestStandaloneE2E(t *testing.T) {
	mosq, ftBin := os.Getenv("VIGILO_MOSQUITTO"), os.Getenv("VIGILO_FT_BIN")
	if mosq == "" || ftBin == "" {
		t.Skip("imposta VIGILO_MOSQUITTO e VIGILO_FT_BIN")
	}
	var ntfyHits atomic.Int32
	ntfy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { ntfyHits.Add(1) }))
	defer ntfy.Close()

	a := newTestApp(t, true)
	a.broker = &LocalBroker{bin: mosq, port: 31883}
	a.ft = &FleetTelemetry{bin: ftBin, dir: t.TempDir(), certs: a.certs}
	const vin = "5YJ3E7EA1KF000000"
	_ = a.store.Update(func(s *State) {
		s.VIN, s.VehicleName = vin, "Test"
		s.Ntfy = &NtfyConfig{Server: ntfy.URL, Topic: "vigilo-e2e"}
	})
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()

	// Stesso ordine di App.Setup: certificati, broker, Bus, fleet-telemetry.
	const host = "vigilo-e2e.example.ts.net"
	if err := a.certs.Ensure(host); err != nil {
		t.Fatal(err)
	}
	m, err := a.mqttService(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer a.broker.cmd.Process.Kill()
	if err := a.bus.Ensure(m); err != nil {
		t.Fatal(err)
	}
	if err := a.ft.Ensure(m); err != nil {
		t.Fatal(err)
	}
	defer a.ft.cmd.Process.Kill()

	caPEM, _ := a.certs.CAPEM()
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM([]byte(caPEM))
	var conn *tls.Conn
	for i := 0; i < 40; i++ {
		conn, err = tls.Dial("tcp", telemetryListen, &tls.Config{RootCAs: pool, ServerName: host})
		if err == nil {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("fleet-telemetry non raggiungibile in TLS: %v", err)
	}
	conn.Close()
	if !a.ft.Running() || !a.bus.Connected() {
		t.Fatalf("componenti non attivi: ft=%v bus=%v", a.ft.Running(), a.bus.Connected())
	}

	// Il broker interno deve aver ricevuto la connessione di fleet-telemetry (client vigilo-fleet-telemetry):
	// lo verifichiamo pubblicando come farebbe lui.
	pub := mqtt.NewClient(mqtt.NewClientOptions().AddBroker(fmt.Sprintf("tcp://127.0.0.1:%d", m.Port)).SetClientID("e2e-pub"))
	if tok := pub.Connect(); !tok.WaitTimeout(5*time.Second) || tok.Error() != nil {
		t.Fatalf("connessione al broker interno: %v", tok.Error())
	}
	defer pub.Disconnect(100)
	// Clienti collegati al broker interno: Vigilo, fleet-telemetry e questo test (mosquitto aggiorna $SYS ogni 10 s).
	clients := make(chan string, 10)
	pub.Subscribe("$SYS/broker/clients/connected", 0, func(_ mqtt.Client, msg mqtt.Message) { clients <- string(msg.Payload()) })
	sysDeadline := time.After(25 * time.Second)
	for n := ""; n < "3" || len(n) != 1; {
		select {
		case n = <-clients:
			t.Logf("clienti MQTT collegati: %s", n)
		case <-sysDeadline:
			t.Fatalf("fleet-telemetry non risulta collegato al broker interno (ultimo valore %q)", n)
		}
	}
	pub.Publish(topicBase+"/"+vin+"/connectivity", 1, false, `{"Status":"CONNECTED"}`).Wait()
	pub.Publish(topicBase+"/"+vin+"/v/SentryMode", 1, false, `"SentryModeStateArmed"`).Wait()
	pub.Publish(topicBase+"/"+vin+"/v/Soc", 1, false, `78.4`).Wait()
	time.Sleep(300 * time.Millisecond)
	pub.Publish(topicBase+"/"+vin+"/v/SentryMode", 1, false, `"SentryModeStateAware"`).Wait()

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) && (a.store.Get().Vehicle.Sentry != "Aware" || ntfyHits.Load() == 0) {
		time.Sleep(100 * time.Millisecond)
	}
	if s := a.store.Get().Vehicle; s.Sentry != "Aware" || s.Battery == nil || *s.Battery != 78.4 || !s.Streaming {
		t.Fatalf("stato inatteso: %+v", s)
	}
	if ntfyHits.Load() != 1 {
		t.Fatalf("notifiche ntfy: %d", ntfyHits.Load())
	}

	// L'app abbinata vede l'evento con la stessa API del componente aggiuntivo.
	req := httptest.NewRequest("GET", "/api/v1/events?after=0&limit=50", nil)
	req.Header.Set("Authorization", "Bearer "+a.store.Get().PairingToken)
	rec := httptest.NewRecorder()
	a.PublicHandler().ServeHTTP(rec, req)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), EvAware) {
		t.Fatalf("eventi per l'app: %d %s", rec.Code, rec.Body)
	}
	for _, c := range a.Checks(ctx, false) {
		if (c.ID == "mqtt" || c.ID == "telemetry_server") && !c.OK {
			t.Fatalf("controllo %s fallito: %s", c.ID, c.Detail)
		}
	}
}
