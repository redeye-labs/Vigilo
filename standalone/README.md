# Vigilo standalone (Docker, senza Home Assistant)

*English below.*

Lo stesso server del componente aggiuntivo Home Assistant (`addon/vigilo/server`), avviato con
`-mode standalone` in un container Docker. Il componente aggiuntivo non cambia: la modalità standalone si
attiva solo in questo container.

> **In prova:** non ancora provata con un'auto vera. Vedi «Provarla senza toccare Home Assistant» qui sotto.

## Cosa c'è nel container

| Componente | Ruolo |
|---|---|
| `vigilo` | procedura guidata (porta 8099), login Tesla, chiave virtuale, firma dei comandi, configurazione telemetria, API per l'app |
| nodo Tailscale (tsnet) | `https://<nome>.<tailnet>.ts.net`: Funnel su 443 (HTTPS, chiave pubblica, login, API app) e 8443 (TCP grezzo verso fleet-telemetry, mTLS dell'auto intatto) |
| `fleet-telemetry` (Tesla, v0.10.0) | riceve i dati dell'auto su 127.0.0.1:4443 con la CA privata di Vigilo |
| `mosquitto` | broker MQTT interno su 127.0.0.1:11883, mai esposto: porta i dati da fleet-telemetry a Vigilo |

Ordine di avvio (automatico): interfaccia → Tailscale → (quando il nodo ha un nome) certificati → broker →
collegamento MQTT di Vigilo → fleet-telemetry → apertura della porta 8443. Se un componente si ferma,
Vigilo lo riavvia entro 5–60 secondi.

## Variabili (file `.env`, tutte facoltative)

| Variabile | Predefinito | Significato |
|---|---|---|
| `VIGILO_TS_HOSTNAME` | `vigilo` | nome del nodo Tailscale (a-z, 0-9, `-`). Usa un nome diverso se Vigilo gira già su Home Assistant |
| `TS_AUTHKEY` | vuoto | chiave Tailscale `tskey-auth-…`. Se vuota, la pagina mostra «Accedi a Tailscale» |
| `VIGILO_UI_PASSWORD` | generata | password della pagina (utente `vigilo`). Se vuota, Vigilo la crea in `data/ui_password` |
| `VIGILO_REGION` | `eu` | regione Tesla al primo avvio (`eu` o `na`), modificabile al passo 2 |
| `MQTT_HOST`, `MQTT_PORT`, `MQTT_USERNAME`, `MQTT_PASSWORD` | vuoti | broker esterno al posto di quello interno: utile se hai Home Assistant in Docker (senza Supervisor) e vuoi le entità MQTT |
| `TZ` | `Europe/Rome` | fuso orario degli orari negli avvisi |

La chiave Tailscale serve solo al primo avvio: lo stato del nodo resta in `data/tailscale`. Per il Funnel il
nodo deve avere l'attributo `funnel`; se usi una chiave con tag, il tag deve comparire in `nodeAttrs`.

## Notifiche

1. **App Vigilo (consigliata, nessun servizio esterno).** Dopo l'abbinamento, in Impostazioni attiva
   «Notifiche allarme anche dall'app» e «Suona anche in silenzioso». L'app legge `/api/v1/events` circa ogni
   18 secondi e mostra una notifica fissa silenziosa, perché Android la richiede.
2. **ntfy (facoltativo, istantaneo).** Installa l'app «ntfy» sul telefono e iscriviti a un argomento. Poi,
   al passo 6 della pagina, inserisci lo stesso argomento (il pulsante «Genera» ne crea uno casuale) e premi
   «Salva» e «Notifica ntfy di prova».
   - Su `https://ntfy.sh`, gratuito, chi conosce l'argomento può leggere gli avvisi: usa un nome lungo e
     casuale.
   - Con un server ntfy tuo puoi usare un token di accesso.
   - Priorità: Panic 5 (massima), Aware 4, problemi 3. Valgono la pausa tra due Aware e gli interruttori
     del passo 6.

## Provarla senza toccare Home Assistant

Vigilo su Home Assistant continua a funzionare se segui queste regole:

- **Altro nome Tailscale:** `VIGILO_TS_HOSTNAME=vigilo-docker`.
- **Altra app Tesla:** crea una seconda app su developer.tesla.com (es. «Vigilo Docker») con Allowed Origin
  e Redirect URI del nuovo nodo. **Non modificare l'app usata da Home Assistant:** cambiarne il dominio la
  scollegherebbe. Imposta anche per la nuova app il limite di spesa a 0.
- La seconda app aggiunge **una seconda chiave virtuale** sull'auto (max 20 chiavi) e **una seconda
  configurazione telemetria**. L'auto ne accetta 3 in streaming contemporaneamente: se hai ancora la
  vecchia app Tesla non eliminata, potrebbe occupare un posto.
- Per una prova a metà, senza auto: passo 1 (Tailscale e Funnel), «Controlla anche da internet» (chiave
  pubblica raggiungibile) e abbinamento dell'app al passo 7 su un secondo telefono. Il telefono abbinato a
  Home Assistant resta com'è.
- Finita la prova: `docker compose down`. In Tesla elimina la seconda app, poi togli la sua chiave
  dall'auto (Comandi › Blocchi). In Tailscale elimina il nodo `vigilo-docker`.

## Soluzione dei problemi

| Sintomo | Cosa fare |
|---|---|
| La pagina chiede la password e non la accetta | utente `vigilo`. La password è in `data/ui_password` o in `VIGILO_UI_PASSWORD`. Se cambi `.env`, riavvia con `docker compose up -d` |
| «Funnel non attivo» | nella console Tailscale abilita HTTPS Certificates e l'attributo `funnel` per il nodo |
| «Broker MQTT» rosso | guarda le righe `mosquitto:` in `docker compose logs`. Con un broker esterno, controlla le variabili `MQTT_*` |
| «Server telemetria» rosso | parte solo dopo il passo 1 (serve il nome del nodo). Guarda le righe `fleet-telemetry:` |
| Container `unhealthy` | `docker compose logs --tail 100` |

## Costruire per più architetture

```bash
docker buildx build --platform linux/amd64,linux/arm64 -f standalone/Dockerfile -t vigilo-standalone .
```
Esegui il comando dalla radice del repository. `standalone/ghcr-workflow.yml` è un workflow pronto che fa
lo stesso su GHCR e parte solo a mano. Per usarlo va copiato in `.github/workflows/` dal sito GitHub.

---

# Vigilo standalone (Docker, without Home Assistant)

The same server as the Home Assistant add-on (`addon/vigilo/server`), started with `-mode standalone` in a
Docker container. The add-on does not change: standalone mode only turns on in this container.

> **Preview:** not yet tested with a real car. See "Try it without touching Home Assistant" below.

**What runs inside:**

- `vigilo`: the wizard on port 8099 and the app API.
- A tsnet Tailscale node: Funnel on 443 (HTTPS) and 8443 (raw TCP to fleet-telemetry, so the car's mTLS
  stays intact).
- Tesla's `fleet-telemetry` v0.10.0 on 127.0.0.1:4443.
- An internal `mosquitto` broker on 127.0.0.1:11883. It is never exposed.

**Variables** (`.env`, all optional):

- `VIGILO_TS_HOSTNAME`: default `vigilo`.
- `TS_AUTHKEY`: if empty, the wizard shows the Tailscale login link.
- `VIGILO_UI_PASSWORD`: if empty, Vigilo generates one in `data/ui_password`. The user is `vigilo`.
- `VIGILO_REGION`: `eu` or `na`.
- `MQTT_HOST`, `MQTT_PORT`, `MQTT_USERNAME`, `MQTT_PASSWORD`: an external broker instead of the internal one.
- `TZ`: time zone used in alert times.

**Notifications:**

1. **Vigilo app:** enable "Notifiche allarme anche dall'app". The app polls `/api/v1/events` about every
   18 s.
2. **Optional ntfy:** set the server and topic in wizard step 6. It also supports an access token for
   self-hosted servers. On public ntfy.sh, anyone who knows the topic can read it, so use a long random one.

**Try it without touching Home Assistant:**

- Use another Tailscale hostname (`vigilo-docker`).
- Use a **second Tesla developer app**. Never change the domain of the app Home Assistant uses.
- The second app adds a second virtual key and a second telemetry config. Cars stream to at most 3 apps at
  once.
- Afterwards: `docker compose down`, then delete the second Tesla app, its virtual key and the Tailscale
  node.

**Never forward port 8099 to the internet.** Tesla and the app only use the Funnel.
