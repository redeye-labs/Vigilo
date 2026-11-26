# Vigilo 2.0 — Backup, migrazione e ripristino

## Cosa è protetto su GitHub

Due riferimenti indipendenti, da non eliminare:
- `backup/vigilo-1.3.10-stabile-20261126` (APK Android funzionante nei test reali indicati dall'utente; sorgenti)
- `backup/pre-vigilo2-20261126` (stato prima del nuovo sviluppo)
- `main` resta la versione distribuita. Le sperimentazioni sono in `feature/vigilo-2-foundations`.

Questi branch sono **backup dei sorgenti, non un backup di /data o dell'APK già installato**.

## Due tipi di backup

1. **Esportazione dati Vigilo 2** dall'app: eventi e letture telemetriche, senza credenziali,
   VIN, testi degli eventi o coordinate. È una copia portabile parziale;
   non permette di ricostruire autenticazione Tesla, chiavi, configurazione Tailscale né
   automaticamente tutti gli storici prima del suo avvio.
2. **Backup completo dell'installazione**: necessario per ripristino server e identità.
   La cartella persistente `/data` contiene `state.json`, chiavi crittografiche,
   configurazione telemetria e ora anche `v2-vehicle-key` e registri V2.
   Deve essere protetta con crittografia e non va mai allegata a issue GitHub,
   email o messaggi in chiaro.

## Home Assistant

Usare il meccanismo ufficiale dei backup di Home Assistant includendo i dati
del componente aggiuntivo Vigilo. Verificare che sia possibile recuperare il backup
e che sia cifrato/protetto. Conservare almeno una copia offline prima di aggiornare
il server. Per una prova di ripristino, utilizzare un'installazione di collaudo:
non sovrascrivere il server attivo senza una copia integra precedente.

## Docker standalone

Il volume locale è normalmente `standalone/data` (vedi `standalone/README.md`).
Arrestare il container prima di copiare i file, così le scritture sullo storico
e le chiavi non cambiano durante la copia. Creare quindi un archivio del volume
persistente e cifrarlo con uno strumento affidabile, conservando anche
la password in modo sicuro. Riavviare il container dopo la copia.

Un ripristino completo richiede la stessa struttura dei file e permessi
adatti (cartella 0700, segreti 0600); va prima provato su un ambiente
separato e senza esporre le porte di amministrazione al pubblico.

## Migrazione alla nuova architettura

Il branch sperimentale **non migra i segreti**. Lascia intatti
`state.json` ed `events.json`. Scrive file separati:

- `v2-vehicle-key`: chiave privata per la pseudonimizzazione del VIN.
- `v2-history.json`: eventi sanitizzati con ID del veicolo.
- `v2-modules.json`: preferenze e tariffe.
- `v2-measurements.json` e `v2-measurements.ndjson`: snapshot e journal dei segnali.
- `v2-alert-rules.json`: regole di notifica e stato anti-ripetizione.

**Non eliminare `v2-vehicle-key`**: gli identificativi degli storici
dipendono da questa chiave. Il backup integrale del server ne deve conservare
il valore, anche quando i dati sono esportati separatamente dall'app.

## Criteri prima di distribuire un aggiornamento pubblico

- Test Android e Go tutti verdi.
- Test di riavvio e recupero di un journal danneggiato.
- Backup e ripristino reale su HA e Docker di collaudo.
- Prova di compatibilità su almeno un'auto Tesla collegata e autorizzata.
- Verifica che l'abilitazione/disabilitazione dei moduli ri-configuri correttamente
  Fleet Telemetry, senza degradare la Sentinella.
- Nessuna installazione automatica o merge su `main` finché questi gate non
  sono superati.

L'esportazione JSON/CSV nell'app è volutamente priva della funzione di
ripristino automatica: un import remoto potrebbe sovrascrivere storici
o riferimenti a un altro veicolo. Il ripristino completo è disponibile
tramite i backup dell'installazione.
