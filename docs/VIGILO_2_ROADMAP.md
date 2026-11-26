# Vigilo 2 — Stato tecnico e criteri di rilascio

> **Versione sperimentale.** Non unire su `main`, non sostituire l'add-on funzionante e non pubblicare una release finché test e collaudi reali non sono conclusi.

## Sorgenti protetti

- `backup/vigilo-1.3.10-stabile-20261126`: sorgenti della versione Android confermata funzionante.
- `backup/pre-vigilo2-20261126`: sorgenti pre-sviluppo Vigilo 2.
- `feature/vigilo-2-foundations`: ramo sperimentale, PR #4.
- Il backup GitHub NON contiene e NON deve contenere i dati segreti del server /data.

## Architettura approvata

Un'app Android, server personale Home Assistant o Docker, moduli opt-in, identità veicolo pseudonima, misure Tesla con sorgente e timestamp, comandi manuali confermati, automazioni rischiose disattivate, compatibilità delle API v1.

Il sistema conserva la configurazione v1 in `state.json` e `events.json`. I dati v2 sono separati:
- `v2-history.json`: storico eventi sanitizzato, import legacy idempotente e vincolato al veicolo originario.
- `v2-modules.json`: moduli e tariffa per veicolo.
- `v2-measurements.ndjson` + `v2-measurements.json`: journal e checkpoint atomico della telemetria. Abbiamo scelto un archivio incrementale senza nuovo driver SQL nella preview, per mantenere minimo l'impatto sull'add-on.
- `v2-vehicle-key`: chiave locale usata per ricavare gli identificativi pseudonimi.
- `v2-alert-rules.json`: avvisi automatici e relativo stato.

## Otto moduli — implementazione attuale

| Modulo | Implementato nella preview | Limiti |
| --- | --- | --- |
| Sicurezza intelligente | Eventi Aware/Panic, raggruppamento temporale, riepilogo, CSV privato | Solo eventi archiviati, non filmati o copertura completa |
| Diagnostica generale | Stato connessione, sleep/stale, letture TPMS se supportate | Nessuna diagnosi meccanica certificata |
| Backup e report | Esportazione JSON con checksum, CSV senza messaggi, report settimanale | Non ripristina token e chiavi; backup completo tramite HA/Docker |
| Comandi avanzati | Clima, carica, sportellino, frunk/bagagliaio, limite SOC, temperatura, corrente | Conferma, permessi Tesla, auto dichiarata online, cambio `P` e telemetria aggiornata entro 2 minuti; nessun risveglio automatico |
| Batteria | SOC, autonomia RatedRange, EnergyRemaining, capacità NominalFullPackEnergyKwh, variazione BMS solo con >=30 giorni di dati coerenti | Etichette di freschezza e timestamp; **SoH assoluto non determinabile** senza baseline di capacità nuova documentata |
| Ricariche e costi | Contatore reale sessione, tariffa utente, costo stimato | Non equivale ai kWh e importi fatturati; storico potenzialmente parziale |
| Automazioni avanzate | Avviso sotto SOC, Panic e parcheggio fuori casa (segnale LocatedAtHome), anti-spam, regole server | Solo notifiche; non attiva funzioni fisiche Tesla automaticamente |
| Viaggi e consumi | Distanze ricavate da Odometer e archivio energia totale | Non sono percorsi GPS completi; consumo medio viaggio non disponibile senza misure di energia riferite allo stesso intervallo |

### Più auto

L'app Android può salvare fino a 12 profili cifrati di **server Vigilo personali**, selezionandone uno alla volta. I dati locali del vecchio server vengono svuotati dal cache Room al cambio profilo, poi ricaricati dal server scelto.

**Non è implementata la gestione simultanea di più VIN in una singola installazione server.** Richiederebbe routing MQTT, Fleet, token, scheduler e isolamento del database completo per VIN: non abilitarla in produzione prima del collaudo specifico.

### Telemetria e costi API

I segnali aggiuntivi vengono richiesti soltanto dopo opt-in del modulo, mantenendo invariati i campi Sentinella esistenti. La configurazione firmata viene aggiornata se cambia il set di campi. `LocatedAtHome` è richiesto solo se è anche attiva la regola specifica fuori casa. Nessun polling aggiuntivo periodico dei moduli v2. Verificare sempre limiti e tariffazione sul portale Tesla.

Le funzioni non promettono disponibilità dei segnali su ogni modello o firmware. La telemetria può essere omessa dal veicolo quando una misura è invalida.

## Beta Android senza sostituire la versione funzionante

Nel ramo V2 soltanto, `assembleDebug` genera `io.github.redeyelabs.vigilo.beta` chiamata **Vigilo 2 Beta**. Il workflow `Vigilo checks` su PR #4 compila e testa Android e backend; solo dopo successo carica il file `Vigilo2-Beta-Debug-APK` negli Artifacts. Il keystore debug GitHub è temporaneo: tra build potrebbe essere necessaria la reinstallazione della sola beta. La beta Android necessita comunque di un server che esponga le API `/api/v2/*`.

## Verifiche ancora necessarie PRIMA della release pubblica

- [ ] Ultimo commit: test backend Go + `go vet`, assemble Android e test unitari **tutti verdi**.
- [ ] Test installazione pulita e aggiornamento con backup reale su un server HA di collaudo.
- [ ] Test installazione pulita e aggiornamento con volume persistente su Docker.
- [ ] Collaudo reale Fleet Telemetry Model 3: unità RatedRange, dati BMS, charging counters, TPMS, LocatedAtHome.
- [ ] Collaudo comandi reali su auto parcheggiata, evitando di inviare comandi potenzialmente rischiosi in modo automatico.
- [ ] Verifica costi/permessi Tesla, risveglio auto, supporto firmware e connessione Tailscale.
- [ ] Test ripristino di `/data`, chiavi e journal su ambiente isolato.
- [ ] Verifica UI accessibilità, sistema notifiche e gestione profili multi-server.

Per i criteri di backup vedere [VIGILO_2_BACKUP_AND_RECOVERY.md](VIGILO_2_BACKUP_AND_RECOVERY.md).

**Finché mancano queste verifiche: PR #4 draft, `main` invariato, nessuna release e nessun aggiornamento automatico.**
