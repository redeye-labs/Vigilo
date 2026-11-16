# Changelog

## 1.0.6
- Passo 7: due QR affiancati. Il primo, «Scarica l'app», si inquadra con la fotocamera del telefono
  e apre il download stabile (Vigilo.apk). Il secondo, «Collega l'app», si inquadra da dentro Vigilo
  (Impostazioni → Collega a Home Assistant → Inquadra QR), non con la fotocamera normale.


## 1.0.5
- Licenze dei componenti di terze parti: elenco in THIRD_PARTY_LICENSES.md (e nell'immagine
  in /usr/share/licenses/vigilo), con il testo NOTICE di Eclipse Paho e una copia della
  licenza Apache 2.0. Nota sui marchi nei README.


## 1.0.4
- Procedura guidata, passo 7: istruzioni numerate per installare l'app (link alla pagina Releases),
  aprire Impostazioni → Collega a Home Assistant → Inquadra QR, e inquadrare il QR. Nota su «Rigenera».
- Il passo 7 diventa verde quando almeno un'app si è collegata (es. «1 app collegata»); lo stato si
  aggiorna da solo ogni pochi secondi, senza ricaricare la pagina.
- All'avvio fleet-telemetry parte prima dell'apertura della porta pubblica 8443, e l'inoltro ritenta
  il collegamento per qualche secondo: l'auto non riceve più "connection refused" nel primo minuto.
- Firma in calce alla procedura guidata e alle Impostazioni dell'app.


## 1.0.3
- Corretto: l'auto non riusciva a collegarsi alla telemetria. La porta pubblica 8443 accettava la
  connessione ma la chiudeva subito senza rispondere (l'inoltro "TCPForward" della serve config di
  Tailscale non arrivava a fleet-telemetry). Ora Vigilo apre il Funnel 8443 direttamente e inoltra il TCP
  grezzo a fleet-telemetry (127.0.0.1:4443), senza terminare il TLS: l'auto vede il certificato della
  nostra CA e il suo mTLS arriva intatto.
- Registro: ogni connessione sulla 8443 (indirizzo, SNI, esito, durata, byte); handshake falliti sulla
  443 con client, SNI e motivo; messaggi di Tailscale su Funnel/inoltri non più silenziati.
- Nuovo controllo "Porta telemetria 8443 raggiungibile da internet" (con "Controlla anche da internet"):
  verifica che risponda fleet-telemetry con il certificato giusto per vigilo.<tailnet>.ts.net.

## 1.0.2
- Corretto: "Fleet API (404)" al passo 5. La configurazione della telemetria ora viene firmata in locale
  (Schnorr/P-256, come il proxy ufficiale e tesla-jws) e inviata direttamente a
  `/api/1/vehicles/fleet_telemetry_config_jws` sullo stesso server regionale usato per le altre chiamate
  (prima passava dal proxy, che sceglie la regione per conto suo). In caso di 404 Vigilo controlla se l'auto
  è nell'account collegato e lo dice nel messaggio.
- Registro più utile: per ogni errore Fleet API metodo, server, percorso, codice e risposta di Tesla
  (VIN mascherati, nessun token); esito della registrazione del dominio per ogni regione; verifica della
  chiave pubblica prima della registrazione.
- HTTPS pubblico: il certificato viene richiesto subito dopo l'apertura del Funnel e l'handshake ha più
  tempo, così i primi accessi (es. Tesla che scarica la chiave pubblica) non vanno in timeout. Gli errori
  di handshake sono riassunti in una riga chiara.
- Pulsanti «Copia» funzionanti anche nel riquadro di Home Assistant (alternativa a navigator.clipboard;
  se la copia è bloccata il testo viene selezionato per copiarlo a mano).
- Procedura guidata e documentazione: nota sul portale Tesla che a volte rifiuta gli URL al primo tentativo.

## 1.0.1
- Corretto: "Supervisor non disponibile" anche dentro Home Assistant. L'immagine base usa s6-overlay,
  che azzera l'ambiente del processo e nascondeva il token del Supervisor: ora il token viene letto anche
  da /run/s6/container_environment e l'ambiente viene mantenuto (S6_KEEP_ENV=1). Di conseguenza tornano
  a funzionare il rilevamento automatico di Mosquitto e l'elenco dei telefoni (notify.mobile_app_*).
- Nuove opzioni facoltative mqtt_host, mqtt_port, mqtt_username, mqtt_password, mqtt_ssl per indicare
  il broker MQTT a mano se il rilevamento automatico non funziona.
- La procedura guidata mostra il motivo se non riesce a leggere i telefoni; i telefoni compaiono per primi.
- Il registro indica all'avvio se il token del Supervisor è stato trovato (senza mostrarlo).

## 1.0.0
- Prima versione: nodo Tailscale integrato con Funnel automatico, login Tesla, chiave virtuale,
  proxy comandi ufficiale, firma e invio della configurazione Fleet Telemetry, server fleet-telemetry,
  entità MQTT, notifiche, API e QR code per l'app Android, controllo giornaliero.
