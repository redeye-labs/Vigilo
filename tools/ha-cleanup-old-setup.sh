#!/usr/bin/env bash
# Vigilo – pulizia della VECCHIA installazione (prima del componente aggiuntivo "Vigilo").
#
# Da eseguire UNA volta, SOLO DOPO che il nuovo componente aggiuntivo Vigilo funziona (tutti i controlli verdi),
# nel terminale dell'app "Advanced SSH & Web Terminal" di Home Assistant.
#   - per il passo Tailscale serve "Modalità protezione" DISATTIVATA (comando docker); altrimenti viene saltato.
#   - ogni passo chiede conferma (s/N). Premendo Invio non si cancella nulla.
#
# Cosa fa:
#   1. disinstalla le app (componenti aggiuntivi) "Community TeslaFleet" e "Tesla HTTP Proxy"
#   2. azzera la configurazione Funnel/Serve creata a mano nell'app Tailscale di Home Assistant
#   3. cancella le cartelle /homeassistant/www/vigilo e /share/vigilo
#   4. elenca le vecchie automazioni Vigilo e le elimina SOLO con conferma una per una
# Non tocca il nuovo componente aggiuntivo Vigilo né le automazioni create dai blueprint nuovi.
set -u

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ask()  { local a; read -r -p "$1 [s/N] " a </dev/tty; [[ "$a" =~ ^[sSyY]$ ]]; }
API="http://supervisor/core/api"
TOKEN="${SUPERVISOR_TOKEN:-}"

command -v ha >/dev/null || { echo "Comando 'ha' non trovato: esegui lo script nel terminale dell'app Advanced SSH & Web Terminal."; exit 1; }
# Da HA 2026.2 i componenti aggiuntivi si chiamano "app" (ha apps); prima: ha addons.
HA_APPS="apps"; ha apps --help >/dev/null 2>&1 || HA_APPS="addons"

# ---------- 1. app vecchie ----------
say "1/4 · App della vecchia installazione"
LIST_JSON=$(ha "$HA_APPS" --raw-json 2>/dev/null || ha "$HA_APPS" list --raw-json 2>/dev/null || true)
find_slug() {  # $1 = suffisso dello slug (es. community_teslafleet)
  printf '%s' "$LIST_JSON" | jq -r --arg s "$1" '(.data.addons // .data.apps // [])[] | select(.slug|endswith($s)) | .slug' 2>/dev/null | head -n1
}
for pair in "community_teslafleet|Community TeslaFleet" "tesla_http_proxy|Tesla HTTP Proxy"; do
  suffix=${pair%%|*}; name=${pair#*|}
  slug=$(find_slug "$suffix")
  if [ -z "$slug" ]; then echo "  - $name: non installata, salto."; continue; fi
  echo "  - $name trovata (slug: $slug)"
  if ask "    Disinstallare $name?"; then
    ha "$HA_APPS" stop "$slug" >/dev/null 2>&1 || true
    if ha "$HA_APPS" uninstall "$slug"; then echo "    ✔ disinstallata"; else echo "    ✖ disinstallazione non riuscita (riprova dall'interfaccia)"; fi
  fi
done
echo "  Nota: le vecchie entità MQTT di TeslaFleet (es. sensor.tesla_…) restano finché non elimini il dispositivo"
echo "  in Impostazioni › Dispositivi e servizi › MQTT. La vecchia chiave virtuale si toglie dall'auto: Comandi › Blocchi."

# ---------- 2. Tailscale Funnel/Serve ----------
say "2/4 · Funnel/Serve creati a mano nell'app Tailscale di Home Assistant"
echo "  Il nuovo Vigilo usa un suo nodo Tailscale ('vigilo'): il vecchio Funnel tcp 8443 -> 4443 non serve più."
if command -v docker >/dev/null 2>&1; then
  TSC=$(docker ps --format '{{.Names}}' | grep -E '_tailscale$' | head -n1)
  if [ -n "$TSC" ]; then
    echo "  Configurazione attuale ($TSC):"
    docker exec "$TSC" /opt/tailscale funnel status 2>/dev/null | sed 's/^/    /' || true
    if ask "  Azzerare Funnel e Serve dell'app Tailscale? (l'app riapplica le sue opzioni al riavvio)"; then
      docker exec "$TSC" /opt/tailscale funnel reset >/dev/null 2>&1 || true
      docker exec "$TSC" /opt/tailscale serve reset >/dev/null 2>&1 || true
      slug_ts=${TSC#addon_}
      ha "$HA_APPS" restart "$slug_ts" >/dev/null 2>&1 && echo "    ✔ azzerato, app Tailscale riavviata" || echo "    ✔ azzerato (riavvia l'app Tailscale dall'interfaccia)"
    fi
  else
    echo "  App Tailscale non in esecuzione: salto."
  fi
else
  echo "  'docker' non disponibile (Modalità protezione attiva): salto."
  echo "  In alternativa riavvia l'app Tailscale: il Funnel creato con 'funnel --bg' può restare attivo, quindi"
  echo "  disattiva la Modalità protezione dell'app SSH e rilancia lo script, oppure lascialo: non disturba Vigilo."
fi

# ---------- 3. cartelle ----------
say "3/4 · Cartelle della vecchia installazione"
for d in /homeassistant/www/vigilo /config/www/vigilo /share/vigilo; do
  [ -d "$d" ] || continue
  echo "  - $d ($(du -sh "$d" 2>/dev/null | cut -f1))"
  ls -1 "$d" 2>/dev/null | head -n 20 | sed 's/^/      /'
  if ask "    Cancellare $d?"; then rm -rf -- "$d" && echo "    ✔ cancellata"; fi
done

# ---------- 4. automazioni ----------
say "4/4 · Vecchie automazioni Vigilo"
if [ -z "$TOKEN" ]; then
  echo "  SUPERVISOR_TOKEN non disponibile: controlla a mano in Impostazioni › Automazioni (cerca «Vigilo» e «sentry»)."
  exit 0
fi
hdr=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
STATES=$(curl -fsS "${hdr[@]}" "$API/states" 2>/dev/null) || { echo "  API di Home Assistant non raggiungibile."; exit 0; }
FOUND=0
while IFS=$'\t' read -r ent id name; do
  [ -n "$id" ] && [ "$id" != "null" ] || continue
  CFG=$(curl -fsS "${hdr[@]}" "$API/config/automation/config/$id" 2>/dev/null) || continue
  # Vecchie = parlano di Vigilo o della Sentinella, NON usano i blueprint nuovi e NON usano sensor.vigilo_*.
  if printf '%s' "$CFG" | grep -qiE 'vigilo|sentry_mode|sentinella' \
     && ! printf '%s' "$CFG" | grep -qE 'use_blueprint|sensor\.vigilo_'; then
    FOUND=1
    echo "  - $name  ($ent, id: $id)"
    if ask "    Eliminare questa automazione?"; then
      if curl -fsS -X DELETE "${hdr[@]}" "$API/config/automation/config/$id" >/dev/null; then echo "    ✔ eliminata"; else echo "    ✖ non eliminata (forse è in YAML: toglila a mano)"; fi
    fi
  fi
done < <(printf '%s' "$STATES" | jq -r '.[] | select(.entity_id|startswith("automation.")) | [.entity_id, (.attributes.id // "null"), (.attributes.friendly_name // .entity_id)] | @tsv')
[ "$FOUND" = 1 ] || echo "  Nessuna vecchia automazione trovata."

say "Fatto."
echo "Da fare a mano, fuori da Home Assistant:"
echo "  - Render: sospendi o elimina il vecchio servizio companion (dashboard.render.com)."
echo "  - developer.tesla.com: elimina la vecchia app Tesla quando la nuova «Vigilo» funziona."
echo "  - Telefono: disinstalla la vecchia app Vigilo 1.0.x (la 1.1 è un'app nuova e si installa accanto)."
