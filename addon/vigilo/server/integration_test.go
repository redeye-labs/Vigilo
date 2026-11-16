package main

// Test di integrazione locale (facoltativo):
//   VIGILO_FT_BIN=/percorso/fleet-telemetry VIGILO_MQTT=127.0.0.1:1883 go test -run Integration -v
import (
	"crypto/tls"
	"crypto/x509"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

func TestIntegrationTelemetryAndBus(t *testing.T) {
	bin, broker := os.Getenv("VIGILO_FT_BIN"), os.Getenv("VIGILO_MQTT")
	if bin == "" || broker == "" {
		t.Skip("imposta VIGILO_FT_BIN e VIGILO_MQTT")
	}
	dir := t.TempDir()
	host, portS, _ := strings.Cut(broker, ":")
	port, _ := strconv.Atoi(portS)
	m := &MQTTService{Host: host, Port: port}

	certs := Certs{dir: dir + "/tls"}
	if err := certs.Ensure("vigilo.example.ts.net"); err != nil {
		t.Fatal(err)
	}
	ft := &FleetTelemetry{bin: bin, dir: dir + "/ft", certs: certs}
	if err := ft.Ensure(m); err != nil {
		t.Fatal(err)
	}
	defer func() { ft.cmd.Process.Kill() }()
	caPEM, _ := certs.CAPEM()
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM([]byte(caPEM))
	var conn *tls.Conn
	var err error
	for i := 0; i < 30; i++ {
		conn, err = tls.Dial("tcp", telemetryListen, &tls.Config{RootCAs: pool, ServerName: "vigilo.example.ts.net"})
		if err == nil {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("fleet-telemetry non raggiungibile in TLS: %v", err)
	}
	t.Logf("TLS ok, certificato server verificato con la CA di Vigilo: %s", conn.ConnectionState().PeerCertificates[0].Subject)
	conn.Close()

	store, _ := NewStore(dir)
	_ = store.Update(func(s *State) {
		s.VIN, s.VehicleName = "5YJ3E7EA1KF000000", "Test"
		s.Settings.NotifyTargets = []string{"x"}
	})
	app := &App{store: store, events: NewEventLog(dir), ha: &HA{}}
	app.bus = &Bus{app: app}
	if err := app.bus.Ensure(m); err != nil {
		t.Fatal(err)
	}
	pub := mqtt.NewClient(mqtt.NewClientOptions().AddBroker("tcp://" + broker).SetClientID("test-pub"))
	if tok := pub.Connect(); tok.WaitTimeout(5*time.Second) && tok.Error() != nil {
		t.Fatal(tok.Error())
	}
	got := make(chan string, 10)
	pub.Subscribe("vigilo/state/5yj3e7ea1kf000000/sentry", 1, func(_ mqtt.Client, msg mqtt.Message) { got <- string(msg.Payload()) })
	pub.Subscribe("homeassistant/sensor/vigilo_5yj3e7ea1kf000000/sentinella/config", 1, func(_ mqtt.Client, msg mqtt.Message) { got <- "discovery" })
	time.Sleep(500 * time.Millisecond)
	pub.Publish(topicBase+"/5YJ3E7EA1KF000000/v/SentryMode", 1, false, `"SentryModeStateArmed"`).Wait()
	time.Sleep(300 * time.Millisecond)
	pub.Publish(topicBase+"/5YJ3E7EA1KF000000/v/SentryMode", 1, false, `"SentryModeStateAware"`).Wait()
	pub.Publish(topicBase+"/5YJ3E7EA1KF000000/v/Location", 1, false, `{"latitude":45.1,"longitude":9.2}`).Wait()
	pub.Publish(topicBase+"/5YJ3E7EA1KF000000/connectivity", 1, false, `{"Status":"CONNECTED"}`).Wait()
	deadline := time.After(5 * time.Second)
	seenAware, seenDisc := false, false
	for !(seenAware && seenDisc) {
		select {
		case v := <-got:
			if v == "Aware" {
				seenAware = true
			}
			if v == "discovery" {
				seenDisc = true
			}
		case <-deadline:
			t.Fatalf("stato non pubblicato (aware=%v discovery=%v)", seenAware, seenDisc)
		}
	}
	s := store.Get()
	if s.Vehicle.Sentry != "Aware" || s.Vehicle.Lat == nil || !s.Vehicle.Streaming {
		t.Fatalf("stato inatteso: %+v", s.Vehicle)
	}
	evs := app.events.List(0, 10)
	found := false
	for _, e := range evs {
		if e.Type == EvAware {
			found = true
		}
	}
	if !found {
		t.Fatalf("evento Aware non registrato: %+v", evs)
	}
}
