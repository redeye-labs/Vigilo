package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type App struct {
	store      *Store
	events     *EventLog
	tesla      *Tesla
	tailnet    *Tailnet
	ha         *HA
	standalone bool         // Docker senza Home Assistant (vedi standalone.go)
	broker     *LocalBroker // broker MQTT interno, solo in modalità standalone
	mqttManual *MQTTService // broker dalle opzioni (facoltativo)
	certs      Certs
	ft         *FleetTelemetry
	bus        *Bus
	version    string
	v2Metrics *V2Metrics
	v2Modules *V2ModuleStore
	v2Identity *V2VehicleIdentity

	mu            sync.Mutex
	auto          automation
	lastAwareSent time.Time
	lastPanicSent time.Time
	lastSetupMsg  string
	mqttErr       string
}

// ---------- telemetria in arrivo ----------

func (a *App) OnTelemetryField(field string, val any) {
	var sentryChange, prevSentry string
	_ = a.store.Update(func(s *State) {
		v := &s.Vehicle
		v.LastTelemetry = time.Now().UnixMilli()
		v.Streaming = true
		v.Online = true
		v.ConnState = "online"
		switch field {
		case "SentryMode":
			ns := normalizeEnum(val, "SentryModeState")
			if ns != v.Sentry {
				prevSentry, sentryChange = v.Sentry, ns
				v.Sentry, v.SentryAt = ns, time.Now().UnixMilli()
			}
		case "Locked":
			if b, ok := val.(bool); ok {
				v.Locked = &b
			}
		case "DoorState":
			open := false
			switch d := val.(type) {
			case map[string]any:
				for _, x := range d {
					if b, ok := x.(bool); ok && b {
						open = true
					}
				}
			case string:
				open = strings.Contains(strings.ToLower(d), "open")
			}
			v.DoorsOpen = &open
		case "FdWindow", "FpWindow", "RdWindow", "RpWindow":
			// Un solo finestrino aperto basta: teniamo lo stato per finestrino in memoria semplice.
			s := normalizeEnum(val, "WindowState")
			a.setWindow(field, s != "Closed" && s != "Unknown" && s != "")
			w := a.anyWindowOpen()
			v.WindowsOpen = &w
		case "Location":
			if m, ok := val.(map[string]any); ok {
				la, ok1 := num(m["latitude"])
				lo, ok2 := num(m["longitude"])
				if ok1 && ok2 && (la != 0 || lo != 0) {
					v.Lat, v.Lon = &la, &lo
				}
			}
		case "Soc", "BatteryLevel":
			if f, ok := num(val); ok {
				v.Battery = &f
			}
		case "EstBatteryRange", "RatedRange":
			updateRangeFromTelemetry(v, field, val)
		case "Gear":
			v.Gear = normalizeEnum(val, "ShiftState")
		case "DetailedChargeState":
			v.Charging = normalizeEnum(val, "DetailedChargeState")
		}
		v.UpdatedAt = time.Now().UnixMilli()
	})
	if sentryChange != "" {
		a.onSentry(prevSentry, sentryChange)
	}
	a.recordV2Telemetry(field,val)
	a.bus.PublishState()
}

// Fleet Telemetry fornisce i chilometri già convertiti; la Fleet API separata usa miglia.
// RatedRange rappresenta l'autonomia nominale visualizzata da Tesla e ha priorità
// su EstBatteryRange, la stima legata alle condizioni di guida.
func updateRangeFromTelemetry(v *VehicleState, field string, val any) {
	f, ok := num(val)
	if !ok || f < 0 {
		return
	}
	switch field {
	case "RatedRange":
		v.RangeKm, v.RangeRated = &f, true
	case "EstBatteryRange":
		if !v.RangeRated {
			v.RangeKm = &f
		}
	}
}

var windowsMu sync.Mutex
var windows = map[string]bool{}

func (a *App) setWindow(k string, open bool) {
	windowsMu.Lock()
	windows[k] = open
	windowsMu.Unlock()
}
func (a *App) anyWindowOpen() bool {
	windowsMu.Lock()
	defer windowsMu.Unlock()
	for _, o := range windows {
		if o {
			return true
		}
	}
	return false
}

func (a *App) OnConnectivity(connected bool) {
	var changed bool
	_ = a.store.Update(func(s *State) {
		changed = s.Vehicle.Streaming != connected
		s.Vehicle.Streaming = connected
		if connected {
			s.Vehicle.LastTelemetry = time.Now().UnixMilli()
		}
	})
	if changed && connected {
		a.events.Add(EvConnect, "Auto collegata", "La telemetria è attiva.")
	}
	a.bus.PublishState()
}

func (a *App) onSentry(prev, now string) {
	s := a.store.Get()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if !a.standalone {
		_ = a.ha.FireEvent(ctx, "vigilo_sentry", map[string]any{"state": now, "previous": prev})
	}
	hm := time.Now().Format("15:04")
	switch now {
	case "Aware":
		a.events.Add(EvAware, "Qualcuno vicino all'auto", "Sentinella in allerta alle "+hm+".")
		a.autoReact(now)
		a.mu.Lock()
		throttle := time.Duration(s.Settings.AwareThrottleSec) * time.Second
		send := s.Settings.NotifyAware && time.Since(a.lastAwareSent) >= throttle
		if send {
			a.lastAwareSent = time.Now()
		}
		a.mu.Unlock()
		if send {
			a.notifyAll(ctx, s.Settings.NotifyTargets, "Sentinella: attenzione",
				"Qualcuno si è fermato vicino all'auto ("+hm+").", map[string]any{
					"channel": "Sentinella", "importance": "high", "priority": "high", "ttl": 0,
					"tag": "vigilo-sentinella", "notification_icon": "mdi:shield-car", "color": "#E53935",
				})
		}
	case "Panic":
		a.events.Add(EvPanic, "ALLARME", "L'allarme della Sentinella sta suonando ("+hm+").")
		a.autoReact(now)
		a.mu.Lock()
		send := s.Settings.NotifyPanic && time.Since(a.lastPanicSent) >= 30*time.Second
		if send {
			a.lastPanicSent = time.Now()
		}
		a.mu.Unlock()
		if send {
			a.notifyAll(ctx, s.Settings.NotifyTargets, "🚨 ALLARME auto",
				"L'allarme Sentinella sta suonando ("+hm+").", map[string]any{
					"channel": "alarm_stream", "importance": "high", "priority": "high", "ttl": 0,
					"tag": "vigilo-allarme", "sticky": "true", "notification_icon": "mdi:alarm-light", "color": "#E53935",
				})
		}
	case "Off":
		if prev != "" {
			a.events.Add(EvSentry, "Sentinella disattivata", "Stato letto dall'auto.")
		}
	case "Armed":
		if prev == "Off" || prev == "" {
			a.events.Add(EvSentry, "Sentinella attivata", "Stato letto dall'auto.")
		}
	}
}

func (a *App) notifyAll(ctx context.Context, targets []string, title, msg string, data map[string]any) {
	if a.standalone {
		a.notifyStandalone(ctx, title, msg, data)
		return
	}
	for _, t := range targets {
		if err := a.ha.Notify(ctx, t, title, msg, data); err != nil {
			log.Printf("notifica a %s non riuscita: %v", t, err)
		}
	}
}

// ---------- comandi ----------

func (a *App) RunCommand(ctx context.Context, name, source string) (string, error) {
	return a.runCommandAs(ctx, name, source, EvCommand)
}

// runCommandAs invia il comando e lo registra nello storico con il tipo indicato.
func (a *App) runCommandAs(ctx context.Context, name, source, evType string) (string, error) {
	s := a.store.Get()
	if s.VIN == "" {
		return "", errors.New("nessuna auto selezionata")
	}
	msg, err := a.tesla.Command(ctx, s.VIN, name)
	if err != nil {
		a.events.Add(EvProblem, "Comando non riuscito", err.Error())
		return "", err
	}
	a.events.Add(evType, msg, "Da "+source+".")
	switch name {
	case "sentry_on":
		_ = a.store.Update(func(s *State) {
			if s.Vehicle.Sentry == "" || s.Vehicle.Sentry == "Off" {
				s.Vehicle.Sentry = "Armed"
			}
		})
	case "sentry_off":
		_ = a.store.Update(func(s *State) { s.Vehicle.Sentry = "Off" })
	case "lock", "unlock":
		b := name == "lock"
		_ = a.store.Update(func(s *State) { s.Vehicle.Locked = &b })
	}
	a.bus.PublishState()
	return msg, nil
}

// Refresh legge i dati dalla Fleet API solo se l'auto è già sveglia.
func (a *App) Refresh(ctx context.Context) error {
	s := a.store.Get()
	if s.VIN == "" {
		return errors.New("nessuna auto selezionata")
	}
	v := s.Vehicle
	if err := a.tesla.VehicleData(ctx, s.VIN, &v); err != nil {
		return err
	}
	_ = a.store.Update(func(st *State) {
		streaming, last := st.Vehicle.Streaming, st.Vehicle.LastTelemetry
		// La telemetria è più fresca della Fleet API per la Sentinella: se attiva, conserviamo il suo valore.
		if streaming && time.Since(time.UnixMilli(last)) < 10*time.Minute && st.Vehicle.Sentry != "" && v.Sentry != "Off" {
			v.Sentry = st.Vehicle.Sentry
		}
		v.Streaming, v.LastTelemetry = streaming, last
		st.Vehicle = v
	})
	a.bus.PublishState()
	return nil
}

// ---------- configurazione automatica ----------

// mqttService: broker manuale dalle opzioni se impostato, altrimenti quello del Supervisor (Mosquitto).
func (a *App) mqttService(ctx context.Context) (*MQTTService, error) {
	if a.mqttManual != nil {
		return a.mqttManual, nil
	}
	if a.standalone {
		return a.broker.Ensure(ctx)
	}
	return a.ha.MQTT(ctx)
}

// Setup esegue i passi automatici che sono pronti: certificati, telemetria, MQTT, dominio, config auto.
func (a *App) Setup(ctx context.Context) {
	host := a.tailnet.Host()
	if host != "" {
		if err := a.certs.Ensure(host); err != nil {
			log.Printf("certificati: %v", err)
		}
	}
	if m, err := a.mqttService(ctx); err == nil {
		a.setMQTTErr("")
		if err := a.bus.Ensure(m); err != nil {
			a.setMQTTErr("connessione MQTT: " + err.Error())
		}
		if host != "" {
			if err := a.ft.Ensure(m); err != nil {
				log.Printf("fleet-telemetry: %v", err)
			}
		}
	} else {
		a.setMQTTErr(err.Error())
	}
	s := a.store.Get()
	if host == "" || s.AccessToken == "" {
		return
	}
	if s.PartnerDomain != host && a.tailnet.Status().Funnel {
		// Tesla scarica la chiave pubblica durante la registrazione: verifichiamo prima che sia raggiungibile.
		// (Solo diagnostica: non blocca, perché da dentro la rete il Funnel potrebbe non essere raggiungibile.)
		if ok, why := checkPublicKey(ctx, host, a.tesla.PublicKeyPEM()); !ok {
			log.Printf("attenzione: chiave pubblica su https://%s/.well-known/... %s", host, why)
		} else {
			log.Printf("chiave pubblica raggiungibile su https://%s/.well-known/appspecific/com.tesla.3p.public-key.pem", host)
		}
		if err := a.tesla.RegisterPartner(ctx, host); err != nil {
			a.setupMsg(err.Error())
			return
		}
		a.events.Add(EvSetup, "Dominio registrato", host)
	}
	s = a.store.Get()
	if s.VIN == "" {
		if vs, err := a.tesla.Vehicles(ctx); err == nil && len(vs) == 1 {
			_ = a.store.Update(func(st *State) { st.VIN, st.VehicleName = vs[0].VIN, vs[0].Name })
			a.bus.Announce()
		}
		return
	}
	a.bus.Announce()
	a.EnsureTelemetryConfig(ctx, false)
}

func (a *App) setMQTTErr(e string) {
	a.mu.Lock()
	a.mqttErr = e
	a.mu.Unlock()
}

// clearSetupMsgIf cancella l'ultimo messaggio di configurazione se soddisfa la condizione.
func (a *App) clearSetupMsgIf(match func(string) bool) {
	a.mu.Lock()
	if a.lastSetupMsg != "" && match(a.lastSetupMsg) {
		a.lastSetupMsg = ""
	}
	a.mu.Unlock()
}

// currentSetupMsg restituisce l'ultimo messaggio di configurazione, ma lo considera superato (e lo cancella)
// quando i controlli dicono che chiave, configurazione e dati dall'auto sono tutti a posto.
func (a *App) currentSetupMsg(checks []Check) string {
	ok := map[string]bool{}
	for _, c := range checks {
		ok[c.ID] = c.OK
	}
	if ok["key"] && ok["config"] && ok["car"] {
		a.clearSetupMsgIf(func(string) bool { return true })
		return ""
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.lastSetupMsg
}

func (a *App) setupMsg(m string) {
	a.mu.Lock()
	changed := a.lastSetupMsg != m
	a.lastSetupMsg = m
	a.mu.Unlock()
	if changed && m != "" {
		log.Printf("configurazione: %s", m)
	}
}

// EnsureTelemetryConfig invia la configurazione se manca, se è per un altro host o se scade entro 30 giorni.
// Non sveglia mai l'auto: Tesla la consegna alla prossima connessione.
func (a *App) EnsureTelemetryConfig(ctx context.Context, force bool) (string, error) {
	s := a.store.Get()
	host := a.tailnet.Host()
	if host == "" || s.VIN == "" {
		return "", errors.New("Tailscale o auto non ancora pronti")
	}
	ts, err := a.tesla.TelemetryConfig(ctx, s.VIN)
	if err == nil {
		// Chiave ora associata: l'eventuale errore "chiave non associata" di un tentativo precedente è superato.
		if ts.KeyPaired != nil && *ts.KeyPaired {
			a.clearSetupMsgIf(func(m string) bool { return strings.Contains(m, describeSkip("missing_key")) })
		}
		_ = a.store.Update(func(st *State) {
			if ts.KeyPaired != nil {
				st.KeyPaired = ts.KeyPaired
			}
			st.TelemetrySynced = ts.Synced
			if ts.Exp > 0 {
				st.TelemetryExp = ts.Exp
			}
		})
	}
	need := force || s.TelemetryFieldsVersion < 2 || err != nil || ts.Missing || ts.Hostname != host ||
		(ts.Exp > 0 && time.Until(time.Unix(ts.Exp, 0)) < 30*24*time.Hour)
	if !need {
		a.setupMsg("") // configurazione presente sull'auto: un errore precedente non vale più
		return "Configurazione già presente sull'auto.", nil
	}
	ca, cerr := a.certs.CAPEM()
	if cerr != nil {
		return "", errors.New("certificati non ancora pronti")
	}
	msg, serr := a.tesla.SendTelemetryConfig(ctx, s.VIN, host, telemetryPort, ca)
	if serr != nil {
		a.setupMsg(serr.Error())
		return "", serr
	}
	_ = a.store.Update(func(st *State) { st.TelemetryFieldsVersion = 2 })
	a.setupMsg("")
	a.events.Add(EvSetup, "Configurazione inviata all'auto", "Valida per 364 giorni, si rinnova da sola.")
	return msg, nil
}

// ---------- controllo di salute ----------

type Check struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	OK     bool   `json:"ok"`
	Detail string `json:"detail"`
}

func (a *App) Checks(ctx context.Context, deep bool) []Check {
	s := a.store.Get()
	ts := a.tailnet.Status()
	var out []Check
	add := func(id, label string, ok bool, detail string) {
		out = append(out, Check{ID: id, Label: label, OK: ok, Detail: detail})
	}
	add("tailscale", "Tailscale collegato", ts.State == "Running" && ts.Host != "", ts.State)
	add("funnel", "Funnel attivo (443 e 8443)", ts.Funnel, ts.Error)
	a.mu.Lock()
	mqttErr := a.mqttErr
	a.mu.Unlock()
	add("mqtt", "Broker MQTT", a.bus.Connected(), mqttErr)
	add("telemetry_server", "Server telemetria avviato", a.ft.Running(), a.ft.lastErr)
	add("credentials", "Client ID e Secret inseriti", s.ClientID != "" && s.ClientSecret != "", "")
	add("login", "Accesso Tesla", s.RefreshToken != "", "")
	if deep && ts.Host != "" {
		ok, detail := checkPublicKey(ctx, ts.Host, a.tesla.PublicKeyPEM())
		add("pubkey", "Chiave pubblica visibile da internet", ok, detail)
		ca, _ := a.certs.CAPEM()
		ok, detail = checkTelemetryPort(ctx, ts.Host, telemetryPort, ca)
		add("telemetry_port", "Porta telemetria 8443 raggiungibile da internet", ok, detail)
	}
	add("partner", "Dominio registrato presso Tesla", s.PartnerDomain != "" && s.PartnerDomain == ts.Host, s.PartnerDomain)
	add("vehicle", "Auto selezionata", s.VIN != "", s.VehicleName)
	kp := s.KeyPaired != nil && *s.KeyPaired
	add("key", "Chiave virtuale associata all'auto", kp, "")
	exp := ""
	if s.TelemetryExp > 0 {
		exp = "scade il " + time.Unix(s.TelemetryExp, 0).Format("02/01/2006")
	}
	add("config", "Configurazione telemetria sull'auto", s.TelemetryExp > nowUnix() && s.TelemetryHost == ts.Host && ts.Host != "", exp)
	connected := s.Vehicle.LastTelemetry > 0
	detail := "in attesa del primo collegamento (avvia l'auto o apri l'app Tesla)"
	if connected {
		detail = "ultimo dato: " + time.UnixMilli(s.Vehicle.LastTelemetry).Format("02/01 15:04")
	}
	add("car", "L'auto ha inviato dati", connected, detail)
	return out
}

// checkTelemetryPort si collega a host:8443 come farebbe l'auto e verifica che risponda fleet-telemetry
// con il certificato della nostra CA per quel nome. (Senza certificato client l'handshake può poi essere
// rifiutato: va bene, conta che il certificato del server sia quello giusto.)
func checkTelemetryPort(ctx context.Context, host string, port int, caPEM string) (bool, string) {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM([]byte(caPEM)) {
		return false, "CA della telemetria non pronta"
	}
	verified := false
	d := &tls.Dialer{NetDialer: &net.Dialer{Timeout: 15 * time.Second}, Config: &tls.Config{
		RootCAs: pool, ServerName: host, MinVersion: tls.VersionTLS12,
		VerifyConnection: func(cs tls.ConnectionState) error {
			verified = len(cs.PeerCertificates) > 0
			return nil
		},
	}}
	cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	c, err := d.DialContext(cctx, "tcp", fmt.Sprintf("%s:%d", host, port))
	if c != nil {
		c.Close()
	}
	if verified {
		return true, ""
	}
	if err != nil {
		return false, "nessuna risposta TLS valida: " + err.Error()
	}
	return false, "risposta senza certificato"
}

func checkPublicKey(ctx context.Context, host string, want []byte) (bool, string) {
	c := &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12}}}
	req, _ := http.NewRequestWithContext(ctx, "GET", "https://"+host+"/.well-known/appspecific/com.tesla.3p.public-key.pem", nil)
	resp, err := c.Do(req)
	if err != nil {
		return false, "non raggiungibile: " + err.Error()
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
	if resp.StatusCode != 200 {
		return false, fmt.Sprintf("risposta %d", resp.StatusCode)
	}
	if strings.TrimSpace(string(b)) != strings.TrimSpace(string(want)) {
		return false, "il file pubblicato non corrisponde alla chiave di Vigilo"
	}
	return true, ""
}

// Loop: configurazione automatica; all'avvio ritenta ogni 5 secondi finché fleet-telemetry
// è in ascolto, poi una volta al minuto, con controllo di salute giornaliero.
func (a *App) Loop(ctx context.Context) {
	lastHealth := time.Now().Add(-23 * time.Hour) // primo controllo dopo circa un'ora
	for {
		sctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
		a.Setup(sctx)
		// rinnovo token preventivo
		if a.store.Get().RefreshToken != "" {
			if _, err := a.tesla.Token(sctx); err != nil {
				log.Printf("token: %v", err)
			}
		}
		if time.Since(lastHealth) > 24*time.Hour && a.store.Get().VIN != "" {
			lastHealth = time.Now()
			a.dailyHealth(sctx)
		}
		cancel()
		wait := time.Minute
		if !a.ft.Running() {
			wait = 5 * time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
	}
}

func (a *App) dailyHealth(ctx context.Context) {
	var problems []string
	for _, c := range a.Checks(ctx, true) {
		if !c.OK && c.ID != "car" {
			p := c.Label
			if c.Detail != "" {
				p += " (" + c.Detail + ")"
			}
			problems = append(problems, p)
		}
	}
	if len(problems) == 0 {
		_ = a.store.Update(func(s *State) { s.LastHealthOK = nowUnix() })
		return
	}
	msg := "Controllo giornaliero: " + strings.Join(problems, "; ") + ". Apri Vigilo in Home Assistant per i dettagli."
	if a.standalone {
		msg = "Controllo giornaliero: " + strings.Join(problems, "; ") + ". Apri la pagina di Vigilo per i dettagli."
	}
	a.events.Add(EvProblem, "Vigilo: qualcosa non va", msg)
	s := a.store.Get()
	if !a.standalone {
		_ = a.ha.PersistentNotification(ctx, "vigilo_health", "Vigilo: qualcosa non va", msg)
	}
	if s.Settings.NotifyProblems {
		a.notifyAll(ctx, s.Settings.NotifyTargets, "Vigilo: qualcosa non va", msg, map[string]any{"tag": "vigilo-salute"})
	}
}
