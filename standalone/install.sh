#!/usr/bin/env bash
# Vigilo standalone – installazione automatica per Linux / NAS / Raspberry Pi (64 bit).
#
#   curl -fsSL https://raw.githubusercontent.com/redeye-labs/Vigilo/main/standalone/install.sh | bash
#
# Rilanciarlo aggiorna Vigilo e conserva dati (standalone/data) e configurazione (standalone/.env).
# Disinstallare:  curl -fsSL .../install.sh | bash -s -- --uninstall
#
# Variabili facoltative (utili anche senza tastiera, es. da uno script):
#   VIGILO_DIR=~/vigilo            cartella di installazione
#   VIGILO_TS_HOSTNAME=vigilo-docker   nome del nodo Tailscale (senza domanda)
#   VIGILO_YES=1                   risponde «sì» alle domande (es. installazione di Docker)
#   VIGILO_REF=main   ramo da scaricare;  VIGILO_SRC=/percorso  copia locale (sviluppo)
set -euo pipefail

REPO="redeye-labs/Vigilo"
REF="${VIGILO_REF:-main}"
DIR="${VIGILO_DIR:-$HOME/vigilo}"
DEFAULT_HOST="vigilo-docker"
PORT=8099

if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; N=$'\033[0m'; else B=; G=; R=; Y=; N=; fi
STEP=0
step() { STEP=$((STEP+1)); printf '\n%s[%d] %s%s\n' "$B" "$STEP" "$*" "$N"; }
ok()   { printf '    %s✓%s %s\n' "$G" "$N" "$*"; }
warn() { printf '    %s!%s %s\n' "$Y" "$N" "$*"; }
die()  { printf '\n%s✗ %s%s\n' "$R" "$*" "$N" >&2; exit 1; }

# Le domande si leggono dal terminale anche con "curl | bash" (lo stdin è lo script).
TTY=""
if [ -r /dev/tty ] && { : </dev/tty; } 2>/dev/null; then TTY=/dev/tty; fi
ask() { # ask "domanda" "predefinito" -> risposta su stdout
  local q="$1" def="${2:-}" ans=""
  if [ -n "$TTY" ]; then
    printf '    %s ' "$q" >/dev/tty
    IFS= read -r ans </dev/tty || ans=""
  fi
  printf '%s' "${ans:-$def}"
}
confirm() { # confirm "domanda" -> 0 se sì
  [ "${VIGILO_YES:-}" = "1" ] && return 0
  [ -n "$TTY" ] || return 1
  local a; a="$(ask "$1 [s/N]" "n")"
  case "$a" in s|S|si|SI|Si|sì|y|Y|yes) return 0 ;; esac
  return 1
}

SUDO=""
if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then SUDO="sudo"; fi
DOCKER=(docker)
COMPOSE=()

pick_docker() {
  if docker info >/dev/null 2>&1; then DOCKER=(docker)
  elif [ -n "$SUDO" ] && $SUDO docker info >/dev/null 2>&1; then DOCKER=(sudo docker)
  else return 1; fi
}
pick_compose() {
  if "${DOCKER[@]}" compose version >/dev/null 2>&1; then COMPOSE=("${DOCKER[@]}" compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    if [ "${DOCKER[0]}" = "sudo" ]; then COMPOSE=(sudo docker-compose); else COMPOSE=(docker-compose); fi
  else return 1; fi
}

uninstall() {
  step "Disinstallazione di Vigilo"
  pick_docker || die "Docker non risponde: impossibile fermare il container."
  if [ -f "$DIR/standalone/docker-compose.yml" ] && pick_compose; then
    (cd "$DIR/standalone" && "${COMPOSE[@]}" down) && ok "container fermato e rimosso"
  else
    "${DOCKER[@]}" rm -f vigilo >/dev/null 2>&1 && ok "container rimosso" || warn "nessun container vigilo trovato"
  fi
  "${DOCKER[@]}" image rm vigilo-standalone:local >/dev/null 2>&1 && ok "immagine rimossa" || true
  if [ -d "$DIR" ]; then
    warn "In $DIR/standalone/data ci sono chiavi e token di Vigilo."
    if confirm "Cancellare anche la cartella $DIR con tutti i dati?"; then
      $SUDO rm -rf "$DIR" && ok "cartella cancellata"
    else
      ok "cartella conservata: $DIR"
    fi
  fi
  printf '\nRicorda: elimina il nodo su login.tailscale.com e, se non la usi più, l'"'"'app su developer.tesla.com.\n'
  exit 0
}

[ "${1:-}" = "--uninstall" ] && uninstall

printf '%sVigilo – installazione automatica (versione Docker, senza Home Assistant)%s\n' "$B" "$N"

# ---------------------------------------------------------------------------
step "Controllo del sistema"
case "$(uname -m)" in
  x86_64|amd64|aarch64|arm64) ok "architettura $(uname -m)" ;;
  *) die "Architettura $(uname -m) non supportata: servono amd64 o arm64 (su Raspberry Pi usa il sistema a 64 bit)." ;;
esac
for c in curl tar; do command -v "$c" >/dev/null 2>&1 || die "Manca il comando «$c»: installalo e rilancia."; done
ok "curl e tar presenti"

# ---------------------------------------------------------------------------
step "Controllo di Docker"
if ! command -v docker >/dev/null 2>&1; then
  warn "Docker non è installato."
  if confirm "Installarlo ora con lo script ufficiale (get.docker.com)?"; then
    curl -fsSL https://get.docker.com | $SUDO sh || die "Installazione di Docker non riuscita."
    $SUDO systemctl enable --now docker >/dev/null 2>&1 || true
  else
    die "Installa Docker (https://docs.docker.com/engine/install/) e rilancia questo comando."
  fi
fi
if ! pick_docker; then
  $SUDO systemctl start docker >/dev/null 2>&1 || true
  sleep 3
  pick_docker || die "Docker è installato ma non risponde. Avvialo (es. sudo systemctl start docker) e rilancia."
fi
ok "Docker risponde$([ "${DOCKER[0]}" = sudo ] && echo ' (con sudo)')"
if ! pick_compose; then
  warn "Docker Compose non trovato."
  if command -v apt-get >/dev/null 2>&1 && confirm "Installare il plugin docker-compose-plugin con apt?"; then
    $SUDO apt-get update -qq && $SUDO apt-get install -y -qq docker-compose-plugin || true
  fi
  pick_compose || die "Serve Docker Compose (v2): https://docs.docker.com/compose/install/"
fi
ok "Compose: $("${COMPOSE[@]}" version --short 2>/dev/null || "${COMPOSE[@]}" version | head -1)"

# ---------------------------------------------------------------------------
step "Scaricamento di Vigilo in $DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if [ -n "${VIGILO_SRC:-}" ]; then
  [ -f "$VIGILO_SRC/standalone/Dockerfile" ] || die "VIGILO_SRC non contiene standalone/Dockerfile"
  mkdir -p "$TMP/src" && (cd "$VIGILO_SRC" && tar --exclude=./standalone/data --exclude=./standalone/.env --exclude=./.git -cf - .) | (cd "$TMP/src" && tar -xf -)
  ok "copia locale da $VIGILO_SRC"
else
  curl -fsSL "https://codeload.github.com/$REPO/tar.gz/refs/heads/$REF" -o "$TMP/vigilo.tgz" \
    || die "Download non riuscito: controlla la connessione a internet."
  mkdir -p "$TMP/src" && tar -xzf "$TMP/vigilo.tgz" -C "$TMP/src" --strip-components=1
  ok "scaricato il ramo $REF"
fi
[ -f "$TMP/src/standalone/docker-compose.yml" ] || die "Archivio incompleto."
mkdir -p "$DIR"
if [ -d "$DIR/standalone" ]; then
  # Aggiornamento: si sostituisce tutto tranne dati e configurazione.
  find "$DIR" -mindepth 1 -maxdepth 1 ! -name standalone -exec rm -rf {} +
  find "$DIR/standalone" -mindepth 1 -maxdepth 1 ! -name data ! -name .env ! -name .image-release -exec rm -rf {} +
  ok "aggiornamento: data/ e .env conservati"
fi
(cd "$TMP/src" && tar --exclude=./standalone/data --exclude=./standalone/.env -cf - .) | (cd "$DIR" && tar -xf -)
ok "file pronti"

# ---------------------------------------------------------------------------
step "Configurazione (.env)"
ENVF="$DIR/standalone/.env"
cur=""
[ -f "$ENVF" ] && cur="$(sed -n 's/^VIGILO_TS_HOSTNAME=//p' "$ENVF" | tail -1)"
def="${VIGILO_TS_HOSTNAME:-${cur:-$DEFAULT_HOST}}"
host="$def"
if [ -z "${VIGILO_TS_HOSTNAME:-}" ]; then
  host="$(ask "Nome del dispositivo su Tailscale? (Invio = $def)" "$def")"
fi
host="$(printf '%s' "$host" | tr 'A-Z' 'a-z' | tr -d '[:space:]')"
printf '%s' "$host" | grep -Eq '^[a-z0-9-]{1,40}$' || die "Nome non valido «$host»: solo lettere minuscole, numeri e '-' (max 40)."
[ -f "$ENVF" ] || cp "$DIR/standalone/.env.example" "$ENVF"
if grep -q '^VIGILO_TS_HOSTNAME=' "$ENVF"; then
  sed -i "s/^VIGILO_TS_HOSTNAME=.*/VIGILO_TS_HOSTNAME=$host/" "$ENVF"
else
  printf '\nVIGILO_TS_HOSTNAME=%s\n' "$host" >>"$ENVF"
fi
chmod 600 "$ENVF" 2>/dev/null || true
ok "nome Tailscale: $host"

# ---------------------------------------------------------------------------
step "Avvio di Vigilo (la prima volta qualche minuto: scarica circa 70 MB)"
# Un vecchio container «vigilo» di un'altra cartella bloccherebbe l'avvio: lo togliamo (i dati sono nella cartella).
proj="$("${DOCKER[@]}" inspect -f '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' vigilo 2>/dev/null || true)"
if [ -n "$proj" ] && [ "$proj" != "$DIR/standalone" ]; then
  warn "trovato un container vigilo di un'altra cartella ($proj): lo sostituisco"
  "${DOCKER[@]}" rm -f vigilo >/dev/null
fi
# retry N "cosa" comando...: ripete con pause crescenti (errori di rete passeggeri, es. "tls: bad record MAC").
retry() {
  local n="$1" what="$2" t; shift 2
  for t in $(seq 1 "$n"); do
    "$@" && return 0
    if [ "$t" -lt "$n" ]; then
      warn "$what: tentativo $t di $n non riuscito (spesso è un errore di rete passeggero), riprovo tra $((10 * t)) secondi"
      sleep $((10 * t))
    fi
  done
  return 1
}
IMAGE="${VIGILO_IMAGE:-ghcr.io/redeye-labs/vigilo-standalone:latest}"
RELTAG="${VIGILO_RELEASE_TAG:-docker-v1.0.8}"
MARKER="$DIR/standalone/.image-release"
# Immagine già pronta dalla release su GitHub (file .tar.gz, verificato con sha256), poi "docker load".
image_from_release() {
  local arch json line url size digest f
  arch="$("${DOCKER[@]}" version -f '{{.Server.Arch}}' 2>/dev/null || true)"
  case "$arch" in amd64|arm64) ;; *) case "$(uname -m)" in aarch64|arm64) arch=arm64 ;; *) arch=amd64 ;; esac ;; esac
  json="$(curl -fsSL -H 'User-Agent: vigilo-installer' "https://api.github.com/repos/$REPO/releases/tags/$RELTAG" 2>/dev/null)" \
    || { warn "non riesco a leggere la release $RELTAG su GitHub"; return 1; }
  # Nel JSON di un asset "size" e "digest" vengono prima di "browser_download_url".
  line="$(printf '%s' "$json" | tr ',{}' '\n\n\n' | awk -v want="vigilo-standalone-$arch.tar.gz" '
    /"size":/ { s=$0; sub(/.*"size": */, "", s) }
    /"digest":/ { d=$0; sub(/.*"digest": *"/, "", d); sub(/".*/, "", d) }
    /"browser_download_url":/ { u=$0; sub(/.*"browser_download_url": *"/, "", u); sub(/".*/, "", u)
      n=split(u, p, "/"); if (p[n] == want) { print u, s, d; exit } }')"
  [ -n "$line" ] || { warn "nella release $RELTAG manca l'immagine per $arch"; return 1; }
  read -r url size digest <<<"$line"
  if "${DOCKER[@]}" image inspect "$IMAGE" >/dev/null 2>&1 && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$digest $url" ]; then
    ok "immagine già aggiornata"; return 0
  fi
  f="$TMP/image.tar.gz"
  dl() {
    printf '        scarico l'"'"'immagine di Vigilo (%s MB, %s)...\n' "$((size / 1048576))" "$arch"
    curl -fL --retry 2 --progress-bar -o "$f" "$url" || return 1
    [ "$(wc -c <"$f" | tr -d ' ')" = "$size" ] || { warn "download incompleto"; return 1; }
    case "$digest" in sha256:*)
      if command -v sha256sum >/dev/null 2>&1; then
        [ "sha256:$(sha256sum "$f" | cut -d' ' -f1)" = "$digest" ] || { warn "download rovinato (impronta diversa)"; return 1; }
      fi ;;
    esac
  }
  retry 4 "Download" dl || return 1
  printf '        carico l'"'"'immagine in Docker...\n'
  "${DOCKER[@]}" load -i "$f" || { warn "docker load non riuscito"; return 1; }
  rm -f "$f"
  printf '%s %s\n' "$digest" "$url" >"$MARKER"
  ok "immagine pronta ($RELTAG, $arch)"
}
compose_in() { (cd "$DIR/standalone" && "${COMPOSE[@]}" "$@"); }
ready=0
if [ "${VIGILO_BUILD:-}" != "1" ]; then
  if image_from_release; then ready=1
  else
    printf '        provo a scaricare l'"'"'immagine con Docker...\n'
    # Se il registro risponde "non esiste / accesso negato" è inutile riprovare.
    pull_once() {
      compose_in pull vigilo 2>&1 | tee "$TMP/pull.log" && return 0
      grep -qiE 'denied|not found|manifest unknown|unauthorized' "$TMP/pull.log" && { NOPULL=1; return 0; }
      return 1
    }
    NOPULL=0
    if retry 3 "docker compose pull" pull_once && [ "$NOPULL" = 0 ]; then ready=1; rm -f "$MARKER"; fi
  fi
fi
if [ "$ready" = 1 ]; then
  compose_in up -d --no-build || die "Avvio non riuscito: guarda i messaggi qui sopra."
else
  warn "immagine pronta non disponibile: compilo Vigilo dal sorgente (5–15 minuti)"
  retry 4 "Compilazione" compose_in up -d --build \
    || die "Avvio non riuscito. Di solito è la connessione: rete instabile, oppure un firewall o un antivirus che controlla il traffico HTTPS e rovina i download. Riprova più tardi o da un'altra rete: i dati restano."
  rm -f "$MARKER"
fi
printf '    attendo che Vigilo sia pronto'
status=""
for _ in $(seq 1 60); do
  status="$("${DOCKER[@]}" inspect -f '{{.State.Health.Status}}' vigilo 2>/dev/null || echo "?")"
  [ "$status" = "healthy" ] && break
  printf '.'; sleep 3
done
printf '\n'
[ "$status" = "healthy" ] || die "Vigilo non risulta pronto (stato: $status). Registro: cd $DIR/standalone && ${COMPOSE[*]} logs --tail 50"
# Immagine della vecchia versione di prova: non serve più.
[ "$IMAGE" = "ghcr.io/redeye-labs/vigilo-standalone:preview" ] || "${DOCKER[@]}" image rm ghcr.io/redeye-labs/vigilo-standalone:preview >/dev/null 2>&1 || true
ok "Vigilo è avviato"

# ---------------------------------------------------------------------------
step "Fatto"
ip=""
if command -v ip >/dev/null 2>&1; then ip="$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)"; fi
[ -n "$ip" ] || ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$ip" ] || ip="<indirizzo-del-server>"
pw_env="$(sed -n 's/^VIGILO_UI_PASSWORD=//p' "$ENVF" | tail -1)"
PWF="$DIR/standalone/data/ui_password"
pw="$pw_env"
if [ -z "$pw" ]; then
  # Vigilo crea la password al primo avvio (il file può essere di root: in quel caso si legge con sudo).
  for _ in $(seq 1 30); do
    pw="$(cat "$PWF" 2>/dev/null || $SUDO cat "$PWF" 2>/dev/null || true)"
    pw="$(printf '%s' "$pw" | tr -d '\r\n')"
    [ -n "$pw" ] && break
    sleep 1
  done
fi
LINE="  ================================================================"
printf '\n%s%s%s\n' "$G" "$LINE" "$N"
printf '   %sPER ENTRARE NELLA PAGINA DI VIGILO%s\n\n' "$B" "$N"
printf '   Indirizzo:     %shttp://%s:%s%s   (dal browser di un computer di casa)\n' "$B" "$ip" "$PORT" "$N"
printf '   Nome utente:   %svigilo%s\n' "$B" "$N"
if [ -n "$pw" ]; then
  printf '   Password:      %s%s%s\n\n' "$B" "$pw" "$N"
else
  printf '   Password:      nel file %s\n\n' "$PWF"
fi
if [ -n "$pw_env" ]; then
  printf '   (password scelta da te nel file .env, voce VIGILO_UI_PASSWORD)\n'
else
  printf '   La password è salvata anche in %s\n' "$PWF"
  printf '   Per rivederla:  %ssudo cat %s%s\n' "$B" "$PWF" "$N"
fi
printf '%s%s%s\n' "$G" "$LINE" "$N"
printf '\n  Poi segui la procedura guidata (passo 1: «Genera link di accesso» per Tailscale).\n'
printf '  Non aprire la porta %s sul router.\n' "$PORT"
printf '  Aggiornare: rilancia lo stesso comando. Disinstallare: aggiungi «-s -- --uninstall» dopo bash.\n'
