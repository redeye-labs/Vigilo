# Vigilo – componente aggiuntivo Home Assistant

*English summary at the bottom.*

Vigilo trasforma Home Assistant nel server della tua auto: riceve in tempo reale gli eventi della
**Sentinella** (Aware = qualcuno vicino all'auto, Panic = allarme) tramite **Fleet Telemetry**,
manda le notifiche al telefono, firma i comandi (Sentinella, chiusura, fari, clacson) e fa da backend
all'app Android **Vigilo**. Tutto gira dentro il componente aggiuntivo: niente server esterni, niente
Render, niente port forwarding sul router.

> Progetto indipendente, non affiliato a Tesla.

## Cosa serve

| | |
|---|---|
| Home Assistant OS o Supervised | amd64 o aarch64 (Raspberry Pi 4/5, mini PC…) |
| **Mosquitto broker** | app (componente aggiuntivo) ufficiale + integrazione MQTT |
| **Account Tailscale** (gratuito) | con **HTTPS** e **Funnel** abilitati (vedi sotto) |
| **Account sviluppatore Tesla** | developer.tesla.com, un'app nuova (es. "Vigilo") |
| Un'auto con chiave virtuale | Model 3/Y/S/X recenti; telemetria: firmware 2024.26+ |

Non serve il componente aggiuntivo Tailscale di Home Assistant: Vigilo include un suo nodo Tailscale
chiamato `vigilo` (indirizzo pubblico `https://vigilo.<tuo-tailnet>.ts.net`).

## Tailscale: HTTPS e Funnel (una volta sola)

Tesla deve raggiungere il tuo Home Assistant da internet per due cose: leggere la chiave pubblica
(porta 443) e inviare la telemetria (porta 8443, mTLS). Tailscale Funnel lo permette senza aprire porte.

1. Pannello Tailscale › **DNS** › abilita **MagicDNS** e **HTTPS Certificates**.
2. Pannello Tailscale › **Access controls** › aggiungi (o verifica) l'attributo funnel, per esempio:

   ```json
   "nodeAttrs": [
     { "target": ["autogroup:member"], "attr": ["funnel"] }
   ]
   ```

   Se usi i tag, puoi limitarlo al solo nodo Vigilo (`"target": ["tag:vigilo"]`).
3. Il Funnel funziona solo sulle porte 443, 8443 e 10000: Vigilo usa 443 e 8443.

## Installazione

1. Impostazioni › **App** (fino a HA 2026.1: «Componenti aggiuntivi») › Store › ⋮ › **Repository** › aggiungi
   `https://github.com/redeye-labs/Vigilo`.
2. Installa **Vigilo**. La prima installazione compila il componente sul tuo Home Assistant:
   può richiedere 5–15 minuti su un Raspberry Pi.
3. Installa e avvia **Mosquitto broker** se non lo hai già.
4. Avvia Vigilo e apri **Vigilo** dalla barra laterale. La procedura guidata fa il resto.

## La procedura guidata (cosa fai tu / cosa fa Vigilo)

| Passo | Tu | Vigilo, in automatico |
|---|---|---|
| 1. Tailscale | premi «Genera link di accesso» e autorizzi il dispositivo `vigilo` | apre il Funnel su 443 e 8443 |
| 2. App Tesla | crei l'app su developer.tesla.com incollando i valori con i pulsanti «Copia»; incolli Client ID e Secret | salva le credenziali solo in `/data` |
| 3. Login | accedi con Tesla e scegli l'auto | genera la chiave, pubblica la chiave pubblica su `/.well-known/…`, registra il dominio presso Tesla (EU e NA) |
| 4. Chiave virtuale | apri il link/QR dal telefono con l'app Tesla e confermi | controlla l'associazione |
| 5. Telemetria | niente (o «Invia di nuovo la configurazione») | crea CA e certificato server, firma e invia la configurazione, avvia il server fleet-telemetry |
| 6. Notifiche | scegli i telefoni e gli avvisi | Aware (con pausa regolabile) e Panic (canale `alarm_stream`) |
| 7. App | inquadri il QR «Scarica l'app» con la fotocamera del telefono (o apri il link), installi Vigilo, poi inquadri il QR «Collega l'app» da dentro Vigilo (Impostazioni → Collega a Home Assistant → Inquadra QR). Non usare «Rigenera» se l'app è già collegata. | abbinamento con token; il passo diventa verde quando l'app è collegata |

Valori da inserire nel portale Tesla (li trovi già pronti nella procedura, con i pulsanti «Copia»):

- **Allowed Origin**: `https://vigilo.<tuo-tailnet>.ts.net`
- **Allowed Redirect URI**: `https://vigilo.<tuo-tailnet>.ts.net/callback`
- **Scope**: Vehicle Information, Vehicle Location, Vehicle Commands, Vehicle Charging Management.

> **Nota:** a volte il portale Tesla rifiuta Allowed Origin o Redirect URI al primo tentativo, o dice che
> non sono validi anche se sono giusti. È un comportamento del loro sito: riprova o premi di nuovo
> **Update** finché non risultano salvati, poi ricontrolla che siano rimasti identici.

## Costi

La Fleet API è a pagamento oltre un credito mensile gratuito. La **telemetria in streaming** e
l'invio della configurazione non svegliano l'auto e costano pochissimo; Vigilo non fa letture
periodiche a pagamento. Se nel portale Tesla hai impostato un **limite di spesa a 0 €**, sei già al
sicuro: oltre il credito gratuito le chiamate vengono rifiutate, non addebitate.

## Manutenzione automatica

- Rinnovo del token Tesla prima della scadenza.
- Rinnovo della configurazione telemetria prima dei 364 giorni (a 30 giorni dalla scadenza), **senza svegliare l'auto**.
- Controllo giornaliero (Tailscale, Funnel, chiave pubblica, MQTT, token, configurazione): se qualcosa
  non va ricevi una notifica persistente in Home Assistant (e sui telefoni scelti, se abilitato).

## Entità create (MQTT)

`sensor.vigilo_sentinella` (Off / Idle / Armed / Aware / Panic / Quiet), `switch.vigilo_sentinella_attiva`,
`binary_sensor` aperta / porte / finestrini / telemetria, `sensor` batteria e autonomia,
`device_tracker` posizione. Evento HA: `vigilo_sentry` (`state`, `vin`).

Blueprint pronti (Aware e Panic): vedi il README del repository.

## Sicurezza

- Client Secret, token Tesla, chiave privata e token di abbinamento restano in `/data` (permessi 0600) e non vengono mai scritti nel registro.
- La pagina di configurazione è raggiungibile solo dall'Ingress di Home Assistant (con il tuo login).
- Su internet (Funnel) sono esposti solo: la chiave pubblica, la pagina di ritorno del login, l'API dell'app
  (protetta da token di abbinamento a 256 bit, rigenerabile) e la porta telemetria (mTLS, accetta solo le auto).

## Licenze

Vigilo è sotto licenza MIT. I componenti di terze parti (fleet-telemetry, vehicle-command, Tailscale, Paho e gli altri) sono elencati, con licenza e indirizzo, in [THIRD_PARTY_LICENSES.md](../../THIRD_PARTY_LICENSES.md). Nell'immagine si trovano in `/usr/share/licenses/vigilo`.

## Opzioni

| Opzione | Default | Note |
|---|---|---|
| `tailscale_hostname` | `vigilo` | nome del nodo nel tailnet |
| `tailscale_authkey` | — | facoltativa: chiave di autenticazione invece del login interattivo |
| `mqtt_host` | — | facoltativo: broker MQTT manuale (es. `core-mosquitto`); vuoto = Mosquitto rilevato in automatico |
| `mqtt_port` | `1883` | porta del broker manuale (8883 con SSL) |
| `mqtt_username` / `mqtt_password` | — | credenziali del broker manuale |
| `mqtt_ssl` | `false` | connessione cifrata al broker manuale |

## Problemi comuni

- **Funnel non attivo**: mancano HTTPS Certificates o l'attributo `funnel` nella policy.
- **MQTT non connesso**: installa/avvia Mosquitto, poi riavvia Vigilo. Se non basta, compila `mqtt_host`, `mqtt_username` e `mqtt_password` nella Configurazione.
- **«L'auto ha inviato dati» rosso**: normale finché l'auto non si sveglia (apri l'auto o guida).
- **Chiave virtuale**: il link `https://tesla.com/_ak/vigilo.<tailnet>.ts.net` va aperto sul telefono con l'app Tesla installata.

---

## English summary

Vigilo turns Home Assistant into your car's backend: real-time Sentry Mode events (Aware/Panic) via
Tesla Fleet Telemetry, notifications, signed commands and the API for the Vigilo Android app. It embeds
its own Tailscale node (`vigilo.<tailnet>.ts.net`) and uses **Tailscale Funnel** on ports 443 and 8443,
so you need **HTTPS certificates** and the **`funnel` node attribute** enabled in your tailnet policy.
Requirements: Home Assistant OS/Supervised (amd64/aarch64), the **Mosquitto** add-on, a free Tailscale
account and a Tesla developer app. Install by adding `https://github.com/redeye-labs/Vigilo` as an add-on
repository, then follow the wizard in the sidebar. Secrets stay in `/data`. Not affiliated with Tesla.
