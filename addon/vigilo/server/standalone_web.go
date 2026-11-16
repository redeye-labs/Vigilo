package main

import (
	"regexp"
	"strings"
)

// La pagina della modalità standalone è web/index.html del componente aggiuntivo (mai modificato),
// con poche sostituzioni fatte qui. Così la versione Home Assistant resta identica byte per byte.
// TestStandalonePage controlla che ogni testo da sostituire esista ancora in index.html.

var standaloneTextSwaps = [][2]string{
	{"così Tesla e l'auto possono raggiungere Home Assistant senza aprire porte sul router.",
		"così Tesla e l'auto possono raggiungere questo server senza aprire porte sul router."},
	{"(resta solo in questo Home Assistant)", "(resta solo su questo server)"},
	{"Da inquadrare <b>dentro Vigilo</b>: Impostazioni, Collega a Home Assistant, Inquadra QR (non con la fotocamera normale).",
		"Da inquadrare <b>dentro Vigilo</b>: Impostazioni, Collega a Home Assistant (vale anche per questo server senza Home Assistant), Inquadra QR (non con la fotocamera normale)."},
	{`<div class="sub">Configurazione guidata.`, `<div class="sub">Configurazione guidata (versione Docker, senza Home Assistant).`},
}

var step6Re = regexp.MustCompile(`(?s)<section class="step" id="s6">.*?</section>`)

const standaloneStep6 = `<section class="step" id="s6"><h2><span class="n">6</span>Notifiche</h2>
    <p>Senza Home Assistant gli avvisi arrivano dall'<b>app Vigilo</b>. Dopo il passo 7 apri Vigilo › <b>Impostazioni</b> e attiva <b>«Notifiche allarme anche dall'app»</b> e <b>«Suona anche in silenzioso»</b>. L'app controlla il server circa ogni 18 secondi.</p>
    <p class="mut">Facoltativo: avviso istantaneo con <b>ntfy</b>, gratuito (app «ntfy» per Android e iPhone). Scegli un argomento difficile da indovinare: su ntfy.sh chi conosce l'argomento può leggere gli avvisi. Lascia vuoto l'argomento per non usarlo.</p>
    <label>Server ntfy</label><input id="nserver" placeholder="https://ntfy.sh" autocomplete="off">
    <label>Argomento (topic)</label><div class="row" style="margin-top:0"><input id="ntopic" autocomplete="off" style="flex:1"><button class="sec small" onclick="genTopic()">Genera</button></div>
    <label>Token di accesso <span class="mut">(solo se il tuo server ntfy lo richiede)</span></label><input id="ntoken" type="password" autocomplete="off">
    <div id="targets" style="display:none"></div>
    <label><input type="checkbox" id="naware" style="width:auto"> Qualcuno vicino all'auto (Aware)</label>
    <label><input type="checkbox" id="npanic" style="width:auto"> Allarme (Panic)</label>
    <label><input type="checkbox" id="nprob" style="width:auto"> Problemi trovati dal controllo giornaliero</label>
    <label>Pausa minima tra due avvisi "qualcuno vicino" (secondi)</label><input id="thr" type="number" min="0" max="3600" step="10">
    <p class="mut">Queste scelte valgono per ntfy. L'app Vigilo mostra comunque ogni evento nello Storico.</p>
    <div class="row"><button onclick="saveSettings()">Salva</button><button class="sec" onclick="testNotify()">Notifica ntfy di prova</button><span id="setmsg" class="mut"></span></div>
  </section>`

// Script aggiunto in fondo: sostituisce solo le funzioni del passo 6.
const standaloneScript = `<script>
let ntfyOn=false;
function genTopic(){const a=new Uint8Array(12);crypto.getRandomValues(a);$('ntopic').value='vigilo-'+[...a].map(b=>b.toString(36).padStart(2,'0')).join('').slice(0,18)}
async function loadSettings(){
  const r=await api('ui/settings'); if(!r.settings) return; settingsLoaded=true;
  const s=r.settings; $('naware').checked=s.notify_aware; $('npanic').checked=s.notify_panic; $('nprob').checked=s.notify_problems; $('thr').value=s.aware_throttle_sec;
  const n=await api('ui/ntfy'); $('nserver').value=n.server||''; $('ntopic').value=n.topic||''; $('ntoken').value=''; $('ntoken').placeholder=n.has_token?'•••••• (salvato)':''; ntfyOn=!!n.topic;
}
async function saveSettings(){
  const r=await api('ui/settings',{method:'POST',body:JSON.stringify({notify_targets:[],notify_aware:$('naware').checked,notify_panic:$('npanic').checked,notify_problems:$('nprob').checked,aware_throttle_sec:parseInt($('thr').value||'120')})});
  if(!r.ok){$('setmsg').textContent=r.error;return}
  const n=await api('ui/ntfy',{method:'POST',body:JSON.stringify({server:$('nserver').value,topic:$('ntopic').value,token:$('ntoken').value})});
  $('setmsg').textContent=n.ok?'Salvato':n.error; settingsLoaded=false; load();
}
async function testNotify(){$('setmsg').textContent='Invio…';const r=await api('ui/test-notify',{method:'POST'});$('setmsg').textContent=r.ok?'Inviata':r.error}
const loadHA=load;
load=async function(deep){await loadHA(deep); done('s6',ntfyOn||((st&&st.paired_apps)||0)>0)};
load();
</script>
`

func standalonePage() []byte {
	b, _ := webFS.ReadFile("web/index.html")
	s := string(b)
	for _, sw := range standaloneTextSwaps {
		s = strings.Replace(s, sw[0], sw[1], 1)
	}
	s = step6Re.ReplaceAllLiteralString(s, standaloneStep6)
	if i := strings.LastIndex(s, "</body>"); i >= 0 {
		s = s[:i] + standaloneScript + s[i:]
	}
	return []byte(s)
}
