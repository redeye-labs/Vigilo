package io.github.redeyelabs.vigilo.ui

import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import io.github.redeyelabs.vigilo.notify.AlertKind
import io.github.redeyelabs.vigilo.notify.Notifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Http
import io.github.redeyelabs.vigilo.api.TeslaAuth
import io.github.redeyelabs.vigilo.data.Region
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: (() -> Unit)? = null, onOpen: (Screen) -> Unit = {}) {
    val p = vm.prefs
    val ctx = LocalContext.current
    val loggedIn by vm.loggedIn.collectAsState()
    val vehicles by vm.vehicles.collectAsState()
    val paired by vm.backendMode.collectAsState()
    val backendUrl by vm.backendUrl.collectAsState()
    val haSettings by vm.backendSettings.collectAsState()
    LaunchedEffect(loggedIn, paired) { if (loggedIn && !paired && p.directLoggedIn) vm.loadVehicles() }
    LaunchedEffect(paired) { if (paired) vm.loadBackendSettings() }
    var direct by remember { mutableStateOf(!paired && p.directLoggedIn) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Impostazioni", "Server Vigilo, avvisi e preferenze.", onBack = onBack)
        HomeAssistantSettings(
            paired = paired,
            backendUrl = backendUrl,
            settings = haSettings,
            onGuide = { onOpen(Screen.GUIDE) },
            onUnpair = { vm.unpairBackend() },
            onChange = { vm.saveBackendSettings(it) },
        )

        if (paired) {
            Section("Notifiche dall'app")
            var notifyFromApp by remember { mutableStateOf(p.notifyAlarmsFromApp) }
            Toggle("Notifiche allarme anche dall'app", p.notifyAlarmsFromApp) {
                notifyFromApp = it
                p.notifyAlarmsFromApp = it
                vm.applyMonitoring()
            }
            Text(
                "Gli avvisi Aware e Panic arrivano anche da Vigilo, con circa 15–20 secondi di ritardo. Resta accesa una notifica fissa e si consuma un po' più di batteria.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notifyFromApp) {
                Toggle("Suona anche in silenzioso", p.alarmBypassSilent) { p.alarmBypassSilent = it }
                Text(
                    "Allarmi con il volume della sveglia, anche con il telefono in silenzioso: la sirena dura qualche secondo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Suoni")
        SoundTests()

        Section("Aggiornamento")
        val autoSec by vm.autoRefreshSeconds.collectAsState()
        Text("Aggiornamento automatico della schermata Auto", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            listOf(0 to "Off", 30 to "30 s", 60 to "60 s", 120 to "2 min").forEach { (sec, label) ->
                FilterChip(selected = autoSec == sec, onClick = { vm.setAutoRefreshSeconds(sec) }, label = { Text(label) })
            }
        }
        Text(
            if (paired) "Con il server Vigilo lo stato arriva in tempo reale dalla telemetria: l'aggiornamento all'apertura e il trascinamento verso il basso bastano."
            else foregroundRefreshHint(autoSec),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Schermata principale")
        Toggle("Mostra la foto dell'auto", p.showHeroCar) { p.showHeroCar = it }
        Text(
            "Spenta di default, così restano in evidenza la Sentinella e gli avvisi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth().clickable { direct = !direct }.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("MODALITÀ DIRETTA (AVANZATA)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Senza server Vigilo: l'app parla direttamente con Tesla. Avvisi solo al controllo periodico.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (direct) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (direct) "Nascondi" else "Mostra",
                tint = VigiloIconGray,
            )
        }
        if (direct) DirectModeSettings(vm, loggedIn = p.directLoggedIn && loggedIn, vehicles = vehicles, paired = paired)

        Section("Crediti")
        Text(
            "Foto dell'auto: Model 3 Highland Pearl White Multi-Coat di Ethan Llamas, Wikimedia Commons, licenza CC BY-SA 4.0.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://commons.wikimedia.org/wiki/File:Tesla_Model_3_Highland_Performance_AWD_Pearl_White_Multi-Coat.jpg"
            )))
        }) { Text("Pagina Wikimedia Commons") }
        Text("Vigilo è un progetto open source indipendente, non affiliato a Tesla. Mappe © OpenStreetMap contributors.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onOpen(Screen.LICENSES) }) { Text("Licenze open source") }
        Spacer(Modifier.height(24.dp))
        Text(
            "Crafted with ❤️ by Saverio",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun DirectModeSettings(
    vm: MainViewModel,
    loggedIn: Boolean,
    vehicles: List<io.github.redeyelabs.vigilo.api.VehicleInfo>,
    paired: Boolean,
) {
    val p = vm.prefs
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (paired) {
            Text(
                "Il telefono è abbinato al server Vigilo: queste impostazioni non vengono usate finché non lo scolleghi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Section("1 · Applicazione Tesla (developer.tesla.com)")
        Field("Client ID", p.clientId) { p.clientId = it }
        Field("Redirect URI (uguale a quello nel portale, es. https://tuo-dominio/callback)", p.redirectUri) { p.redirectUri = it }
        Field("URL companion web (scambio token con segreto lato server)", p.companionUrl) { p.companionUrl = it }
        Field("Chiave companion (facoltativa, APP_KEY)", p.companionKey, secret = true) { p.companionKey = it }
        Field("Client secret (solo senza companion; resta cifrato sul telefono)", p.clientSecret, secret = true) { p.clientSecret = it }
        Text("Regione Fleet API", style = MaterialTheme.typography.labelLarge)
        var region by remember { mutableStateOf(p.region) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Region.entries.forEach { r -> FilterChip(selected = region == r, onClick = { region = r; p.region = r }, label = { Text(r.name) }) }
        }

        Section("2 · Account Tesla")
        if (!loggedIn) {
            Button(onClick = {
                runCatching { TeslaAuth.buildAuthorizeUrl(p) }
                    .onSuccess { CustomTabsIntent.Builder().build().launchUrl(ctx, it) }
                    .onFailure { vm.toast(it.message ?: "Errore") }
            }, modifier = Modifier.fillMaxWidth()) { Text("Accedi con Tesla") }
            var pasted by remember { mutableStateOf("") }
            OutlinedTextField(pasted, { pasted = it }, label = { Text("…oppure incolla qui l'URL di ritorno o il codice") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = pasted.isNotBlank(), onClick = {
                runCatching { TeslaAuth.parseCallback(pasted) }
                    .onSuccess { (code, state) -> vm.handleCallback(code, state) }
                    .onFailure { vm.toast(it.message ?: "URL non valido") }
            }) { Text("Usa codice") }
        } else {
            Text("✔ Collegato a Tesla")
            Text("Veicolo: ${p.vehicleName.ifBlank { "nessuno selezionato" }} ${p.vin.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""}")
            vehicles.forEach { v ->
                OutlinedButton(onClick = { vm.selectVehicle(v) }, modifier = Modifier.fillMaxWidth()) { Text("Usa ${v.name} · ${v.state}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadVehicles() }) { Text("Ricarica veicoli") }
                TextButton(onClick = { vm.logout() }) { Text("Esci") }
            }
        }

        Section("3 · Comandi firmati (Model 3)")
        Text("La Model 3 accetta comandi (Sentinella, clacson, fari, chiusura) solo se firmati con la chiave virtuale. " +
                "Inserisci l'indirizzo di Home Assistant (Tailscale o rete locale): Vigilo si collega al proxy, " +
                "legge il certificato e lo memorizza da solo.", style = MaterialTheme.typography.bodySmall)
        ProxySetup(vm)
        OutlinedButton(onClick = {
            val host = Uri.parse(p.redirectUri).host
            if (host.isNullOrBlank()) vm.toast("Inserisci prima il Redirect URI: il dominio è quello che ospita la chiave pubblica.")
            else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tesla.com/_ak/$host")))
        }) { Text("Associa chiave virtuale all'auto") }

        Section("4 · Monitoraggio")
        Toggle("Controllo periodico in background", p.pollingEnabled) { p.pollingEnabled = it; vm.applyMonitoring() }
        Stepper("Ogni (minuti, minimo 15)", p.pollMinutes, 15, 120, 5) { p.pollMinutes = it; vm.applyMonitoring() }
        Toggle("Leggi i dati completi solo con Sentinella attiva (risparmio)", p.onlyWhenSentry) { p.onlyWhenSentry = it }
        Toggle("Monitoraggio intensivo in primo piano", p.intensiveEnabled) { p.intensiveEnabled = it; vm.applyMonitoring() }
        Stepper("Intervallo intensivo (minuti)", p.intensiveMinutes, 1, 30, 1) { p.intensiveMinutes = it }
        Text(costHint(p.pollMinutes, p.intensiveEnabled, p.intensiveMinutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("5 · Reazione a un evento con Sentinella")
        Toggle("Lampeggia i fari automaticamente", p.autoFlash) { p.autoFlash = it }
        Toggle("Suona il clacson automaticamente", p.autoHonk) { p.autoHonk = it }
        Stepper("Spostamento considerato sospetto (metri)", p.movementMeters, 20, 500, 10) { p.movementMeters = it }

        Section("6 · Avvisi")
        Toggle("Auto lasciata aperta", p.alertUnlocked) { p.alertUnlocked = it }
        Stepper("…dopo (minuti)", p.unlockedMinutes, 1, 60, 1) { p.unlockedMinutes = it }
        Toggle("Finestrino aperto", p.alertWindows) { p.alertWindows = it }
        Toggle("Ricarica completata", p.alertChargeComplete) { p.alertChargeComplete = it }
        Toggle("Batteria bassa", p.alertLowBattery) { p.alertLowBattery = it }
        Stepper("Soglia batteria bassa (%)", p.lowBatteryPct, 5, 50, 5) { p.lowBatteryPct = it }
    }
}

private fun foregroundRefreshHint(seconds: Int): String {
    if (seconds <= 0) {
        return "Aggiornamento automatico spento: lo stato si aggiorna all'apertura dell'app, trascinando verso il basso e dopo un comando."
    }
    val perHour = 3600 / seconds
    val usd = perHour * 30 / 500.0
    return "Se tieni aperta la schermata Auto per un'ora al giorno con l'auto online: circa $perHour letture/giorno ≈ ${"%.1f".format(usd)} \$/mese di «device data» (1 \$ ogni 500). Tesla abbuona 10 \$/mese. Se l'auto dorme queste letture a pagamento non partono."
}

private fun costHint(poll: Int, intensive: Boolean, im: Int): String {
    val perDay = (24 * 60 / poll) + if (intensive) 24 * 60 / im else 0
    val usd = perDay * 30 / 500.0
    return "Stima nel caso peggiore (auto sempre online): ~$perDay letture/giorno ≈ ${"%.1f".format(usd)} $/mese " +
        "di \"device data\" (1 $ ogni 500). Tesla abbuona 10 $/mese. Con l'auto addormentata le letture complete non vengono fatte."
}

@Composable private fun Section(t: String) {
    Text(
        t.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun ProxySetup(vm: MainViewModel) {
    val savedUrl by vm.proxyUrl.collectAsState()
    val savedFp by vm.proxyFingerprint.collectAsState()
    var url by remember { mutableStateOf(savedUrl.ifBlank { "" }) }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(savedUrl) {
        if (savedUrl.isNotBlank()) url = savedUrl
    }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Indirizzo Home Assistant (es. 100.x.y.z o 192.168.1.10)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.commitProxyUrl(url) }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (savedFp.isNotBlank()) {
        Text(
            "Certificato memorizzato (${Http.shortFingerprint(savedFp)}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    Button(onClick = { vm.verifyProxy(url) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (savedFp.isBlank()) "Collega proxy" else "Verifica proxy")
    }
    Row(
        Modifier.fillMaxWidth().clickable { advanced = !advanced },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Avanzate", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (advanced) "Nascondi avanzate" else "Mostra avanzate",
        )
    }
    if (advanced) {
        Text("Impronta SHA-256 del certificato (sola lettura)", style = MaterialTheme.typography.labelLarge)
        Text(
            if (savedFp.isBlank()) "Nessun certificato memorizzato."
            else Http.normalizeFingerprint(savedFp).uppercase().chunked(2).joinToString(":"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (savedFp.isNotBlank()) {
            TextButton(onClick = { vm.resetProxyCertificate() }) { Text("Reimposta certificato") }
        }
    }
}

@Composable
private fun Field(label: String, initial: String, secret: Boolean = false, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; onSave(it) }, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Pulsanti «Prova suono» per ogni tipo di avviso, con l'eventuale problema che impedirebbe di sentirlo. */
@Composable
private fun SoundTests() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Text(
        "Ogni avviso ha il suo suono: sirena per l'allarme, doppio bip quando qualcuno è vicino all'auto, tono discreto per avvisi e problemi. Con «Suona anche in silenzioso» gli allarmi usano il volume della sveglia.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    listOf(
        AlertKind.PANIC to "Allarme",
        AlertKind.AWARE to "Qualcuno vicino",
        AlertKind.INFO to "Avviso",
    ).forEach { (kind, label) ->
        val problem = remember(tick) { Notifier.soundProblem(ctx, kind) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            OutlinedButton(onClick = {
                if (!Notifier.test(ctx, kind)) {
                    Toast.makeText(ctx, "Consenti le notifiche a Vigilo per provare il suono.", Toast.LENGTH_LONG).show()
                }
                tick++
            }) { Text("Prova suono") }
        }
        if (problem != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(problem, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { runCatching { ctx.startActivity(Notifier.channelSettingsIntent(ctx, kind)) }; tick++ }) {
                    Text("Sistema")
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); VigiloSwitch(v, { v = it; onChange(it) })
    }
}

@Composable
private fun Stepper(label: String, initial: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(initial.toFloat()) }
    Column {
        Text("$label: ${v.roundToInt()}")
        VigiloSlider(value = v, onValueChange = { v = ((it / step).roundToInt() * step).toFloat().coerceIn(min.toFloat(), max.toFloat()) },
            valueRange = min.toFloat()..max.toFloat(), onValueChangeFinished = { onChange(v.roundToInt()) })
    }
}
