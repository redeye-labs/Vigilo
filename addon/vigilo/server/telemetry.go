package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"math/big"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

const (
	telemetryListen = "127.0.0.1:4443"
	telemetryPort   = 8443 // porta pubblica del Funnel
	topicBase       = "vigilo/raw"
)

// ---------- certificati per fleet-telemetry ----------

type Certs struct {
	dir string
}

func (c Certs) caPath() string    { return filepath.Join(c.dir, "ca.crt") }
func (c Certs) caKeyPath() string { return filepath.Join(c.dir, "ca.key") }
func (c Certs) certPath() string  { return filepath.Join(c.dir, "server-fullchain.crt") }
func (c Certs) keyPath() string   { return filepath.Join(c.dir, "server.key") }
func (c Certs) hostPath() string  { return filepath.Join(c.dir, "host.txt") }

func (c Certs) CAPEM() (string, error) {
	b, err := os.ReadFile(c.caPath())
	return string(b), err
}

// Ensure crea (una volta) una CA privata da 10 anni e il certificato del server per host (5 anni).
// Se l'host cambia rigenera solo il certificato del server.
func (c Certs) Ensure(host string) error {
	if err := os.MkdirAll(c.dir, 0o700); err != nil {
		return err
	}
	if _, err := os.Stat(c.caPath()); errors.Is(err, os.ErrNotExist) {
		k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		tmpl := &x509.Certificate{
			SerialNumber:          serial(),
			Subject:               pkix.Name{CommonName: "Vigilo Telemetry CA"},
			NotBefore:             time.Now().Add(-time.Hour),
			NotAfter:              time.Now().AddDate(10, 0, 0),
			IsCA:                  true,
			BasicConstraintsValid: true,
			KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		}
		der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &k.PublicKey, k)
		if err != nil {
			return err
		}
		kd, _ := x509.MarshalECPrivateKey(k)
		_ = os.WriteFile(c.caKeyPath(), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kd}), 0o600)
		_ = os.WriteFile(c.caPath(), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644)
		_ = os.Remove(c.hostPath())
	}
	if old, _ := os.ReadFile(c.hostPath()); string(old) == host {
		if _, err := os.Stat(c.certPath()); err == nil {
			return nil
		}
	}
	caB, err := os.ReadFile(c.caPath())
	if err != nil {
		return err
	}
	caKB, err := os.ReadFile(c.caKeyPath())
	if err != nil {
		return err
	}
	cb, _ := pem.Decode(caB)
	kb, _ := pem.Decode(caKB)
	if cb == nil || kb == nil {
		return errors.New("CA danneggiata: elimina /data/tls e riavvia")
	}
	ca, err := x509.ParseCertificate(cb.Bytes)
	if err != nil {
		return err
	}
	caKey, err := x509.ParseECPrivateKey(kb.Bytes)
	if err != nil {
		return err
	}
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: serial(),
		Subject:      pkix.Name{CommonName: host},
		DNSNames:     []string{host},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(5, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca, &k.PublicKey, caKey)
	if err != nil {
		return err
	}
	kd, _ := x509.MarshalECPrivateKey(k)
	chain := append(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), caB...)
	if err := os.WriteFile(c.keyPath(), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kd}), 0o600); err != nil {
		return err
	}
	if err := os.WriteFile(c.certPath(), chain, 0o644); err != nil {
		return err
	}
	log.Printf("certificato telemetria creato per %s", host)
	return os.WriteFile(c.hostPath(), []byte(host), 0o600)
}

func serial() *big.Int {
	n, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	return n
}

// ---------- processo fleet-telemetry (binario ufficiale Tesla) ----------

type FleetTelemetry struct {
	bin, dir string
	certs    Certs
	mu       sync.Mutex
	cmd      *exec.Cmd
	cfgHash  string
	lastErr  string
}

func (f *FleetTelemetry) Running() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.cmd != nil && f.cmd.ProcessState == nil
}

// Ensure scrive la configurazione e (ri)avvia il processo se serve.
func (f *FleetTelemetry) Ensure(m *MQTTService) error {
	if _, err := os.Stat(f.bin); err != nil {
		return fmt.Errorf("binario fleet-telemetry non trovato (%s)", f.bin)
	}
	host, port := "127.0.0.1", 4443
	cfg := map[string]any{
		"host":                     host,
		"port":                     port,
		"log_level":                "info",
		"json_log_enable":          false,
		"namespace":                "vigilo",
		"reliable_ack":             false,
		"transmit_decoded_records": true,
		"rate_limit":               map[string]any{"enabled": true, "message_interval_time": 30, "message_limit": 1000},
		"records": map[string]any{
			"V": []string{"mqtt"}, "alerts": []string{"mqtt"}, "errors": []string{"mqtt"}, "connectivity": []string{"mqtt"},
		},
		"mqtt": map[string]any{
			"broker":     fmt.Sprintf("%s:%d", m.Host, m.Port),
			"client_id":  "vigilo-fleet-telemetry",
			"username":   m.Username,
			"password":   m.Password,
			"topic_base": topicBase,
			"qos":        1,
			"retained":   false,
		},
		"tls": map[string]any{"server_cert": f.certs.certPath(), "server_key": f.certs.keyPath()},
	}
	b, _ := json.MarshalIndent(cfg, "", "  ")
	_ = os.MkdirAll(f.dir, 0o700)
	path := filepath.Join(f.dir, "config.json")
	hash := fmt.Sprintf("%x", b)
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.cmd != nil && f.cmd.ProcessState == nil && hash == f.cfgHash {
		return nil
	}
	if err := os.WriteFile(path, b, 0o600); err != nil {
		return err
	}
	if f.cmd != nil && f.cmd.Process != nil && f.cmd.ProcessState == nil {
		_ = f.cmd.Process.Kill()
		_, _ = f.cmd.Process.Wait()
	}
	cmd := exec.Command(f.bin, "-config", path)
	cmd.Stdout = prefixWriter("fleet-telemetry")
	cmd.Stderr = prefixWriter("fleet-telemetry")
	if err := cmd.Start(); err != nil {
		f.lastErr = err.Error()
		return err
	}
	f.cmd, f.cfgHash = cmd, hash
	go func() {
		err := cmd.Wait()
		f.mu.Lock()
		if f.cmd == cmd {
			f.lastErr = fmt.Sprintf("fleet-telemetry terminato: %v", err)
			f.cfgHash = "" // forza il riavvio al prossimo controllo
		}
		f.mu.Unlock()
		log.Printf("fleet-telemetry terminato: %v", err)
	}()
	log.Printf("fleet-telemetry avviato su %s:%d", host, port)
	return nil
}

type logWriter struct{ p string }

func (w logWriter) Write(b []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(b), "\n"), "\n") {
		if line != "" {
			log.Printf("%s: %s", w.p, line)
		}
	}
	return len(b), nil
}
func prefixWriter(p string) logWriter { return logWriter{p} }

// ---------- MQTT: stato dalla telemetria, entità per Home Assistant, comandi ----------

type Bus struct {
	app       *App
	mu        sync.Mutex
	client    mqtt.Client
	cred      string
	announced string
}

func (b *Bus) Connected() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.client != nil && b.client.IsConnectionOpen()
}

func (b *Bus) Ensure(m *MQTTService) error {
	key := fmt.Sprintf("%s:%d:%s:%s", m.Host, m.Port, m.Username, m.Password)
	b.mu.Lock()
	if b.client != nil && b.cred == key {
		b.mu.Unlock()
		return nil
	}
	if b.client != nil {
		b.client.Disconnect(200)
	}
	b.mu.Unlock()
	opts := mqtt.NewClientOptions().
		AddBroker(fmt.Sprintf("tcp://%s:%d", m.Host, m.Port)).
		SetClientID("vigilo-addon").
		SetUsername(m.Username).SetPassword(m.Password).
		SetAutoReconnect(true).SetConnectRetry(true).SetConnectRetryInterval(10 * time.Second).
		SetOrderMatters(false)
	opts.SetOnConnectHandler(func(c mqtt.Client) {
		c.Subscribe(topicBase+"/#", 1, b.onRaw)
		c.Subscribe("vigilo/cmd/#", 1, b.onCmd)
		c.Subscribe("homeassistant/status", 1, func(mqtt.Client, mqtt.Message) {
			b.mu.Lock()
			b.announced = ""
			b.mu.Unlock()
			b.Announce()
		})
		b.mu.Lock()
		b.announced = ""
		b.mu.Unlock()
		b.Announce()
	})
	c := mqtt.NewClient(opts)
	tok := c.Connect()
	tok.WaitTimeout(10 * time.Second)
	b.mu.Lock()
	b.client, b.cred = c, key
	b.mu.Unlock()
	return tok.Error()
}

func (b *Bus) publish(topic string, retained bool, payload any) {
	b.mu.Lock()
	c := b.client
	b.mu.Unlock()
	if c == nil {
		return
	}
	var data []byte
	switch p := payload.(type) {
	case string:
		data = []byte(p)
	case []byte:
		data = p
	default:
		data, _ = json.Marshal(p)
	}
	c.Publish(topic, 1, retained, data)
}

// Announce pubblica le entità (MQTT discovery). Nomi stabili: sensor.vigilo_sentinella ecc.
func (b *Bus) Announce() {
	s := b.app.store.Get()
	if s.VIN == "" {
		return
	}
	b.mu.Lock()
	if b.announced == s.VIN || b.client == nil {
		b.mu.Unlock()
		return
	}
	b.announced = s.VIN
	b.mu.Unlock()
	id := strings.ToLower(s.VIN)
	name := s.VehicleName
	if name == "" {
		name = "Tesla"
	}
	dev := map[string]any{"identifiers": []string{"vigilo_" + id}, "name": "Vigilo " + name, "manufacturer": "Vigilo", "model": "Fleet Telemetry"}
	st := "vigilo/state/" + id
	avail := []map[string]any{{"topic": "vigilo/status"}}
	ent := func(component, key string, cfg map[string]any) {
		cfg["unique_id"] = "vigilo_" + id + "_" + key
		cfg["object_id"] = "vigilo_" + key
		cfg["device"] = dev
		cfg["availability"] = avail
		b.publish(fmt.Sprintf("homeassistant/%s/vigilo_%s/%s/config", component, id, key), true, cfg)
	}
	ent("sensor", "sentinella", map[string]any{"name": "Sentinella", "state_topic": st + "/sentry", "icon": "mdi:shield-car",
		"device_class": "enum", "options": []string{"Off", "Idle", "Armed", "Aware", "Panic", "Quiet", "Unknown"}})
	ent("switch", "sentinella_attiva", map[string]any{"name": "Sentinella attiva", "state_topic": st + "/sentry_on",
		"command_topic": "vigilo/cmd/" + id + "/sentry", "payload_on": "ON", "payload_off": "OFF", "icon": "mdi:shield-car"})
	ent("binary_sensor", "aperta", map[string]any{"name": "Serratura", "state_topic": st + "/unlocked", "device_class": "lock"})
	ent("binary_sensor", "porte", map[string]any{"name": "Porte", "state_topic": st + "/doors", "device_class": "door"})
	ent("binary_sensor", "finestrini", map[string]any{"name": "Finestrini", "state_topic": st + "/windows", "device_class": "window"})
	ent("binary_sensor", "telemetria", map[string]any{"name": "Telemetria collegata", "state_topic": st + "/streaming",
		"device_class": "connectivity", "entity_category": "diagnostic"})
	ent("sensor", "batteria", map[string]any{"name": "Batteria", "state_topic": st + "/battery", "unit_of_measurement": "%", "device_class": "battery", "state_class": "measurement"})
	ent("sensor", "autonomia", map[string]any{"name": "Autonomia", "state_topic": st + "/range", "unit_of_measurement": "km", "device_class": "distance", "icon": "mdi:map-marker-distance"})
	ent("device_tracker", "posizione", map[string]any{"name": "Posizione", "json_attributes_topic": st + "/location", "source_type": "gps"})
	b.publish("vigilo/status", true, "online")
	b.PublishState()
}

func onOff(v *bool) string {
	if v == nil {
		return "None"
	}
	if *v {
		return "ON"
	}
	return "OFF"
}

func (b *Bus) PublishState() {
	s := b.app.store.Get()
	if s.VIN == "" {
		return
	}
	st := "vigilo/state/" + strings.ToLower(s.VIN)
	v := s.Vehicle
	sentry := v.Sentry
	if sentry == "" {
		sentry = "Unknown"
	}
	b.publish(st+"/sentry", true, sentry)
	on := sentry != "Off" && sentry != "Unknown"
	b.publish(st+"/sentry_on", true, onOff(&on))
	if v.Locked != nil {
		un := !*v.Locked
		b.publish(st+"/unlocked", true, onOff(&un))
	}
	if v.DoorsOpen != nil {
		b.publish(st+"/doors", true, onOff(v.DoorsOpen))
	}
	if v.WindowsOpen != nil {
		b.publish(st+"/windows", true, onOff(v.WindowsOpen))
	}
	b.publish(st+"/streaming", true, onOff(&v.Streaming))
	if v.Battery != nil {
		b.publish(st+"/battery", true, fmt.Sprintf("%.0f", *v.Battery))
	}
	if v.RangeKm != nil {
		b.publish(st+"/range", true, fmt.Sprintf("%.0f", *v.RangeKm))
	}
	if v.Lat != nil && v.Lon != nil {
		b.publish(st+"/location", true, map[string]any{"latitude": *v.Lat, "longitude": *v.Lon, "gps_accuracy": 10})
	}
}

func (b *Bus) onCmd(_ mqtt.Client, m mqtt.Message) {
	parts := strings.Split(m.Topic(), "/")
	if len(parts) != 4 || parts[3] != "sentry" {
		return
	}
	cmd := "sentry_off"
	if strings.EqualFold(string(m.Payload()), "ON") {
		cmd = "sentry_on"
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		if _, err := b.app.RunCommand(ctx, cmd, "Home Assistant"); err != nil {
			log.Printf("comando da Home Assistant non riuscito: %v", err)
		}
	}()
}

// onRaw riceve i messaggi di fleet-telemetry: vigilo/raw/<VIN>/v/<Campo>, .../connectivity, .../alerts/...
func (b *Bus) onRaw(_ mqtt.Client, m mqtt.Message) {
	parts := strings.Split(strings.TrimPrefix(m.Topic(), topicBase+"/"), "/")
	if len(parts) < 2 {
		return
	}
	vin := parts[0]
	s := b.app.store.Get()
	if s.VIN != "" && !strings.EqualFold(vin, s.VIN) {
		return
	}
	switch parts[1] {
	case "v":
		if len(parts) >= 3 {
			var val any
			if json.Unmarshal(m.Payload(), &val) != nil {
				val = string(m.Payload())
			}
			b.app.OnTelemetryField(parts[2], val)
		}
	case "connectivity":
		var c struct {
			Status string `json:"Status"`
		}
		_ = json.Unmarshal(m.Payload(), &c)
		b.app.OnConnectivity(strings.EqualFold(c.Status, "CONNECTED"))
	}
}

// normalizeEnum: "SentryModeStateAware" -> "Aware", "ShiftStateP" -> "P".
func normalizeEnum(v any, prefixes ...string) string {
	s := fmt.Sprint(v)
	for _, p := range prefixes {
		s = strings.TrimPrefix(s, p)
	}
	return s
}
