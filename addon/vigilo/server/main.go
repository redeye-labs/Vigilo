// Vigilo – componente aggiuntivo per Home Assistant (o container Docker autonomo: -mode standalone).
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
	mode := flag.String("mode", "", "ha (componente aggiuntivo, predefinito) o standalone (Docker senza Home Assistant); anche VIGILO_MODE")
	mosqBin := flag.String("mosquitto", "/usr/sbin/mosquitto", "binario mosquitto (broker interno, solo standalone)")
	health := flag.String("healthcheck", "", "controlla l'URL indicato ed esce (per HEALTHCHECK di Docker)")
	flag.Parse()
	if *health != "" {
		os.Exit(healthcheck(*health))
	}
	if *mode == "" {
		*mode = os.Getenv("VIGILO_MODE")
	}
	standalone := *mode == modeStandalone
	if *mode != "" && *mode != modeHA && !standalone {
		log.Fatalf("modalità sconosciuta %q: usa ha o standalone", *mode)
	}
	log.SetFlags(log.LstdFlags)
	if standalone {
		log.Printf("Vigilo %s (standalone, senza Home Assistant)", version)
	} else {
		log.Printf("Vigilo %s", version)
	}

	opts := Options{TailscaleHostname: "vigilo"}
	if b, err := os.ReadFile(filepath.Join(*dataDir, "options.json")); err == nil {
		_ = json.Unmarshal(b, &opts)
	}
	if opts.TailscaleHostname == "" {
		opts.TailscaleHostname = "vigilo"
	}
	var uiPassword string
	if standalone {
		applyStandaloneEnv(&opts)
		if err := os.MkdirAll(*dataDir, 0o700); err != nil {
			log.Fatalf("cartella dati: %v", err)
		}
		pw, src, err := loadUIPassword(*dataDir)
		if err != nil {
			log.Fatalf("password interfaccia: %v", err)
		}
		uiPassword = pw
		log.Printf("interfaccia protetta: utente %q, password da %s", uiUser, src)
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
		standalone: standalone,
	}
	if standalone {
		app.broker = &LocalBroker{bin: *mosqBin, port: localBrokerPort}
		if app.mqttManual == nil {
			log.Printf("MQTT: broker interno (mosquitto su 127.0.0.1:%d)", localBrokerPort)
		}
	} else if src := app.ha.Source(); src != "" {
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
		var r string
		if standalone {
			r = standaloneRegion()
		} else {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			r = regionForCountry(app.ha.Country(ctx))
			cancel()
		}
		_ = store.Update(func(s *State) { s.Region = r })
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// Le nuove API restano separate e di sola lettura. In caso di archivio
	// V2 non disponibile, la vecchia API v1 continua a funzionare normalmente.
	publicHandler := app.PublicHandler()
	v2History, historyErr := OpenV2Archive(*dataDir)
	v2Modules, modulesErr := OpenV2ModuleStore(*dataDir)
	if historyErr != nil || modulesErr != nil {
		log.Printf("Vigilo 2 moduli sperimentali non disponibili: archive=%v modules=%v", historyErr, modulesErr)
	} else {
		muxV2 := http.NewServeMux()
		muxV2.Handle("/api/v2/", &V2PublicAPI{state: store, events: app.events, history: v2History, modules: v2Modules, tesla: tesla})
		muxV2.Handle("/", publicHandler)
		publicHandler = muxV2
	}
	go app.tailnet.Run(ctx, publicHandler)
	go app.Loop(ctx)
	go app.ScheduleLoop(ctx)

	handler := app.IngressHandler()
	if standalone {
		handler = app.StandaloneHandler(uiPassword)
	}
	srv := &http.Server{Addr: *ingress, Handler: handler, ReadHeaderTimeout: 15 * time.Second}
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
