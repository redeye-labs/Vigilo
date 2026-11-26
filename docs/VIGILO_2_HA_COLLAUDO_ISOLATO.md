# Vigilo 2 — collaudo controllato su Home Assistant (fase 1)

**Questa procedura NON aggiorna il Vigilo stabile.** Il risultato della fase 1 è soltanto:
"Home Assistant installa e avvia il backend Vigilo 2 in un add-on distinto".
Non è un test dell'auto né una validazione Fleet API.

## 0. Prerequisiti e regole di sicurezza

- Lascia installato e avviato l'add-on Vigilo attualmente funzionante.
- Non cambiare l'URL del repository Vigilo già presente nel tuo Add-on Store.
- Non ripristinare né copiare `state.json`, token Tesla o chiavi `/data` nel nuovo add-on.
- Non usare il nome/slug `vigilo` per l'add-on beta; il pacchetto usa `vigilo2_smoke`.
- Il pacchetto collaudo forza `VIGILO_ISOLATED_PREVIEW=1` nel **Dockerfile**.
  Questa variabile viene controllata PRIMA di aprire state, Tesla, MQTT, Tailscale,
  Fleet Telemetry e scheduler. Il collaudo non può eseguire comandi Tesla.
- Esegui la prova solo su un'istanza HA dove puoi accedere alla directory host
  `/addons` (SSH a livello host o app di sviluppo con accesso locale agli add-on).
- Non esporre la porta 8099 su Internet. Usa solo Ingress autenticato di Home Assistant.

## 1. Prima: backup ripristinabile

In Home Assistant apri **Impostazioni > Sistema > Backup**. Crea un backup
che includa l'add-on Vigilo **e i suoi dati**; per maggiore sicurezza includi
anche l'intero Home Assistant. Scaricane una copia su un altro dispositivo e
conserva separatamente l'eventuale chiave di cifratura/emergency kit.
Verifica che backup e dimensioni siano plausibili. Non condividere l'archivio
in chat, issue GitHub o repository: contiene credenziali.

Guida ufficiale Home Assistant:
https://www.home-assistant.io/common-tasks/general/backups/

**Gate B0:** backup completato, scaricato e chiave di ripristino disponibile.
Se manca uno di questi tre elementi, FERMA IL COLLAUDO.

## 2. Scarica il pacchetto beta isolato

1. Apri la PR #4: https://github.com/redeye-labs/Vigilo/pull/4
2. Apri la più recente esecuzione *Vigilo checks* con **backend**, **android** e
   **Home Assistant isolated smoke package** verdi.
3. Scarica l'artifact `Vigilo2-HA-Isolated-Smoke`. Decomprimi lo ZIP artifact:
   contiene `vigilo2-ha-smoke.tar.gz` e `vigilo2-ha-smoke.sha256`.
4. Verifica lo SHA-256. NON scaricare il codice dalla branch principale:
   contiene l'add-on con slug `vigilo`, inadatto a questo collaudo.

Questo artifact è temporaneo (14 giorni). Non è una release pubblica.

## 3. Crea un secondo add-on locale

Sul dispositivo HA, mediante accesso amministrativo alla directory **/addons**,
estraine l'archivio. Deve risultare questa struttura:

```text
/addons/
  vigilo2_smoke/
    config.yaml
    Dockerfile
    server/
    ...
```

Il file `config.yaml` del pacchetto riporta:
```yaml
name: Vigilo 2 - Collaudo isolato
slug: vigilo2_smoke
boot: manual_only
ingress: true
ingress_port: 8099
```

**Non spostarlo nella cartella dell'add-on Vigilo originale** e non
sostituire alcun file della sua directory.

In **Impostazioni > Componenti aggiuntivi / Apps > Store**, esegui
l'aggiornamento dell'elenco add-on. Nella categoria degli add-on locali
comparirà *Vigilo 2 - Collaudo isolato*. Se non compare, controlla
che `config.yaml` si trovi subito dentro `/addons/vigilo2_smoke`.

## 4. Primo avvio senza Tesla

1. Seleziona SOLO *Vigilo 2 - Collaudo isolato* e premi **Installa**.
2. Attendi che Home Assistant completi la build della sua immagine.
3. Verifica che **Avvio all'avvio sia disattivato** (boot manual).
4. Premi **Avvia** soltanto sul secondo add-on.
5. Premi **Apri interfaccia Web** tramite Ingress.

Risultato atteso:
- Titolo **Vigilo 2 · Collaudo isolato**.
- Testo **Server di prova avviato correttamente**.
- Nessuna richiesta di login Tesla/Tailscale.
- Nessun broker MQTT aggiuntivo; nessuna configurazione Fleet Telemetry.
- Add-on Vigilo originale ancora avviato, stessa interfaccia, stessi eventi.
- La Tesla e la modalità Sentinella continuano a essere monitorate dal Vigilo originale.

Il nuovo add-on espone solo `/` (pagina informativa) e `/health` (stato di
collaudo); tutte le API `/api/v1/*` e `/api/v2/*` vengono rifiutate.
Non tentare l'abbinamento dell'app Vigilo 2 Beta allo smoke add-on.

## 5. Controlli di regressione

Prima e dopo l'avvio, confronta sull'add-on Vigilo originale:
- Stato monitoraggio e ultimo evento Sentinella.
- Connessione telemetria e MQTT.
- Versione installata e nome add-on.
- Presenza dello storico precedente (non deve scomparire).

Esegui soltanto letture/osservazioni: **non usare i comandi su una Tesla reale
per verificare lo smoke test**.

Se lo smoke test fallisce: arresta e disinstalla soltanto
`vigilo2_smoke`, conservando i log d'avvio **redatti di eventuali segreti**.
Non fermare Vigilo originale; non ripristinare backup senza un danno effettivo.

## 6. Dopo il collaudo isolato

Per collaudare realmente le API Vigilo 2, il sistema Tesla/Fleet e il backup
completo occorre una **seconda istanza di Home Assistant** o un ambiente
di test separato con URL, token, MQTT/Tailscale e Tesla developer app
specifici. La stessa app Tesla/Fleet o lo stesso hostname Tailscale usati
dalla produzione NON devono essere riutilizzati in parallelo.

Il semplice avvio dello smoke add-on **non conclude** i test di integrazione
o il collaudo dei comandi. La distribuzione Vigilo 2 su `main` è bloccata
fino ai gate descritti in `docs/VIGILO_2_ROADMAP.md`.
