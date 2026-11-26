# Vigilo 2.0 — sviluppo modulare (branch sperimentale)

> Questo branch NON è una release e NON va installato su Home Assistant/Docker finché la suite di test, il backup dei dati e la migrazione non sono pronti.

## Riferimenti di ripristino (26 novembre 2026)

- `backup/vigilo-1.3.10-stabile-20261126` — codice Android confermato funzionante prima delle successive modifiche della telemetria.
- `backup/pre-vigilo2-20261126` — snapshot di `main` con le ultime correzioni del server, non ancora collaudate nell'installazione dell'utente.
- `main` e `standalone-docker` non vanno toccati da questo branch.

**Questi riferimenti proteggono i sorgenti GitHub, non /data.** Prima di migrare qualsiasi installazione reale, creare un backup cifrato offline di `/data` (compresi il database e il materiale crittografico), con permessi adeguati e verifica di ripristino. Non pubblicare mai /data nei GitHub Artifacts, nelle issue o nelle release.

## Requisiti approvati

Un'unica app Android, server personale HA o Docker, moduli opzionali, veicoli separati per ID, comandi sensibili disattivati per default, dati misurati e stimati chiaramente distinguibili, setup automatico per utenti non tecnici.

## Regole architetturali

1. Telemetria Tesla condivisa, nessun polling addizionale per moduli disabilitati.
2. `vehicle_id` presente in tutti i futuri record di storico; un dato di un'auto non deve comparire nell'altra.
3. Ogni misura contiene provenienza, qualità, unità, `measured_at` e `received_at`.
4. Funzioni di sicurezza storiche e API v1 rimangono attive; migrare in modo incrementale.
5. Il server esegue automazioni/archiviazione anche col telefono spento; il telefono è un client.
6. Le modifiche al database sono versionate, transazionali e accompagnate da ripristino verificato.
7. Il vecchio `state.json` contiene segreti e non deve essere incluso in esportazioni ordinarie.
8. Per il multi-veicolo, progettare l'isolamento di token, stato, config telemetrica, scheduler, MQTT e storico prima di abilitare un secondo VIN. Il modello dati da solo non costituisce supporto multi-veicolo completo.

## Fase 0 — fondazione

- [x] Salvare i sorgenti pre-sviluppo in branch di backup.
- [x] Tipi di dominio per moduli, provenienza dei dati, identificativi veicolo.
- [x] Raggruppamento eventi Sentinella puro e isolato per auto, con unit test.
- [ ] Collaudare i test con GitHub Actions; correggere ogni fallimento.
- [ ] Scegliere un driver SQLite affidabile e verificare dipendenze, architetture HA/Docker, Go toolchain e versioni.
- [ ] Migrazione non distruttiva da `state.json` e `events.json` con backup e rollback.
- [ ] API v2 versionate, controllo di compatibilità client/server e capabilities per veicolo.
- [ ] Gestione multi-veicolo effettiva, incluse autorizzazioni e segregazione completa.
- [ ] Test installazione/upgrade su HA e Docker.

## Fase 1 — esperienza utente

- [ ] Sicurezza: aggregazione per parcheggio, filtri Aware/Panic, controlli notifiche e preferenze.
- [ ] Diagnostica: Tesla asleep / telemetria stale / server offline / permessi mancanti, senza confonderli.
- [ ] Esportazione e backup dei dati applicativi senza segreti, ripristino transazionale.
- [ ] Report settimanali locali opt-in, con lacune dati chiaramente segnalate.
- [ ] UI Android Jetpack Compose con home essenziale e schermate secondarie.
- [ ] Snapshot test Android e test di integrazione server.

## Fasi successive (non implementate)

- Comandi Tesla avanzati, con controllo capabilities, conferma, autorizzazione e protezione dai risvegli.
- Batteria e SOH: solo valori BMS effettivi o stime documentate, mai numeri inventati.
- Ricariche, prezzi configurabili ed energia reale/consumi stimati distinti.
- Automazioni a condizioni multiple, geofencing e safety guard.
- Viaggi e consumi con raccolta privacy-first e itinerari opzionali.

## Criteri di uscita obbligatori

Build e test Android/Go verdi; compatibilità v1; no esposizione di segreti; migrazione idempotente e reversibile; gestione errori per ogni segnale non supportato; nessun costo Tesla aggiuntivo quando i moduli sono spenti; collaudo manuale su HA e Docker. Nessuna unione su `main` prima di queste verifiche.
