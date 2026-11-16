// Vigilo – componente aggiuntivo per Home Assistant.
// Un solo processo: Tailscale (tsnet + Funnel), login Tesla, chiave virtuale, proxy comandi ufficiale,
// firma e invio della configurazione Fleet Telemetry, server fleet-telemetry (binario Tesla), entità MQTT,
// notifiche e API per l'app Android.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

var version = "dev"

type Options struct {
	TailscaleHostname string `json:"tailscale_hostname"`
	TailscaleAuthKey  string `json:"tailscale_authkey"`
	// Broker MQTT manuale (facoltativo): se mqtt_host è vuoto si usa quello fornito dal Supervisor (Mosquitto).
	MQTTHost     string `json:"mqtt_host"`
	MQTTPort     int    `json:"mqtt_port"`
	MQTTUsername string `json:"mqtt_username"`
	MQTTPassword string `json:"mqtt_password"`
	MQTTSSL      bool   `json:"mqtt_ssl"`
}

// manualMQTT restituisce il broker configurato a mano, o nil se non impostato.
func (o Options) manualMQTT() *MQTTService {
	if o.MQTTHost == "" {
		return nil
	}
	port := o.MQTTPort
	if port == 0 {
		port = 1883
		if o.MQTTSSL {
			port = 8883
		}
	}
	return &MQTTService{Host: o.MQTTHost, Port: port, SSL: o.MQTTSSL, Username: o.MQTTUsername, Password: o.MQTTPassword}
}

func main() {
	dataDir := flag.String("data", "/data", "cartella dati persistente")
	ingress := flag.String("ingress", ":8099", "indirizzo per l'interfaccia Ingress")
	ftBin := flag.String("fleet-telemetry", "/usr/bin/fleet-telemetry", "binario fleet-telemetry")
	flag.Parse()
	log.SetFlags(log.LstdFlags)
	log.Printf("Vigilo %s", version)

	opts := Options{TailscaleHostname: "vigilo"}
	if b, err := os.ReadFile(filepath.Join(*dataDir, "options.json")); err == nil {
		_ = json.Unmarshal(b, &opts)
	}
	if opts.TailscaleHostname == "" {
		opts.TailscaleHostname = "vigilo"
	}

	store, err := NewStore(*dataDir)
	if err != nil {
		log.Fatalf("stato: %v", err)
	}
	tesla, err := NewTesla(store, *dataDir)
	if err != nil {
		log.Fatalf("chiave: %v", err)
	}
	app := &App{
		store:      store,
		events:     NewEventLog(*dataDir),
		tesla:      tesla,
		tailnet:    NewTailnet(*dataDir, opts.TailscaleHostname, opts.TailscaleAuthKey, telemetryListen),
		ha:         NewHA(),
		mqttManual: opts.manualMQTT(),
		certs:      Certs{dir: filepath.Join(*dataDir, "tls")},
		version:    version,
	}
	if src := app.ha.Source(); src != "" {
		log.Printf("Supervisor: token trovato (%s)", src)
	} else {
		log.Printf("Supervisor: token NON trovato; MQTT automatico, notifiche ed eventi non funzioneranno")
	}
	if app.mqttManual != nil {
		log.Printf("MQTT: broker manuale %s:%d", app.mqttManual.Host, app.mqttManual.Port)
	}
	app.ft = &FleetTelemetry{bin: *ftBin, dir: filepath.Join(*dataDir, "fleet-telemetry"), certs: app.certs}
	app.bus = &Bus{app: app}
	if store.Get().Region == "" {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		r := regionForCountry(app.ha.Country(ctx))
		cancel()
		_ = store.Update(func(s *State) { s.Region = r })
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go app.tailnet.Run(ctx, app.PublicHandler())
	go app.Loop(ctx)

	srv := &http.Server{Addr: *ingress, Handler: app.IngressHandler(), ReadHeaderTimeout: 15 * time.Second}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sctx)
	}()
	log.Printf("interfaccia su %s", *ingress)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("ingress: %v", err)
	}
}
