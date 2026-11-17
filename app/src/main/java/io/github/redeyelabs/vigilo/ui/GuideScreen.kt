package io.github.redeyelabs.vigilo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend

const val ADDON_REPOSITORY = "https://github.com/redeye-labs/Vigilo"

/** Guida passo passo con controlli dal vivo del componente aggiuntivo Home Assistant. */
@Composable
fun GuideScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val paired by vm.backendMode.collectAsState()
    val url by vm.backendUrl.collectAsState()
    val checks by vm.backendChecks.collectAsState()
    val loading by vm.backendChecksLoading.collectAsState()
    val error by vm.backendChecksError.collectAsState()
    LaunchedEffect(paired) { if (paired) vm.loadBackendChecks(deep = true) }
    GuideScreenContent(
        paired = paired,
        backendUrl = url,
        checks = checks,
        loading = loading,
        error = error,
        onBack = onBack,
        onCopyRepo = { copy(ctx, "Repository Vigilo", ADDON_REPOSITORY); vm.toast("Indirizzo copiato") },
        onScan = { QrScan.start(ctx, onResult = { vm.pairBackend(it) }, onError = { vm.toast(it) }) },
        onPair = { vm.pairBackend(it) },
        onRecheck = { vm.loadBackendChecks(deep = true) },
    )
}

@Composable
fun GuideScreenContent(
    paired: Boolean,
    backendUrl: String,
    checks: List<Backend.Check>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onCopyRepo: () -> Unit,
    onScan: () -> Unit,
    onPair: (String) -> Unit,
    onRecheck: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            "Guida",
            "Home Assistant fa da server, il telefono mostra l'auto e riceve gli avvisi.",
            onBack = onBack,
        )
        GuideText("Scarica Vigilo solo dal repository ufficiale github.com/redeye-labs/Vigilo. Dalla 1.2.0 la firma dell'APK è nuova: se hai la 1.1.x, disinstallala prima, poi installa questa versione e rabbina di nuovo col QR.")

        GuideStep(1, "Installa il componente aggiuntivo", done = paired) {
            GuideText("In Home Assistant: Impostazioni › App (o «Componenti aggiuntivi») › Store › menu ⋮ › Repository. Aggiungi questo indirizzo e installa «Vigilo».")
            Text(ADDON_REPOSITORY, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            TextButton(onClick = onCopyRepo) { Text("Copia indirizzo") }
            GuideText("Serve anche il componente aggiuntivo «Mosquitto broker» (con l'integrazione MQTT): Vigilo lo usa per creare le entità.")
        }
        GuideStep(2, "Segui la procedura nel componente aggiuntivo", done = paired && checks.isNotEmpty() && checks.all { it.ok }) {
            GuideText("Apri «Vigilo» dalla barra laterale: collega Tailscale, crea l'app su developer.tesla.com con i valori da copiare, accedi a Tesla e associa la chiave virtuale. Il resto (certificati, registrazione, telemetria) è automatico.")
        }
        GuideStep(3, "Abbina questo telefono", done = paired) {
            if (paired) {
                GuideText("Abbinato a:")
                Text(backendUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            } else {
                GuideText("Nella pagina di configurazione di Vigilo (componente aggiuntivo o Docker), ultimo passo: inquadra il QR di abbinamento.")
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scansiona il QR")
                }
                var pasted by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("…oppure incolla il link di abbinamento") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { onPair(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Abbina")
                }
            }
        }

        if (paired) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Controlli dal vivo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = VigiloIconGray)
                else TextButton(onClick = onRecheck) { Text("Ricontrolla") }
            }
            if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = VigiloDanger)
            if (checks.isEmpty() && !loading && error == null) GuideText("Nessun controllo ancora letto.")
            checks.forEach { CheckRow(it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GuideStep(n: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    VigiloCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$n", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (done) Icon(Icons.Filled.Check, contentDescription = "Fatto", tint = VigiloIconGray, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun GuideText(t: String) {
    Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CheckRow(c: Backend.Check) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (c.ok) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = if (c.ok) "OK" else "Da sistemare",
            tint = if (c.ok) VigiloIconGray else VigiloDanger,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.label, style = MaterialTheme.typography.bodyLarge)
            val hint = if (c.ok) c.detail.takeIf { it.isNotBlank() && c.id in setOf("vehicle", "config", "car") } else checkHint(c)
            if (!hint.isNullOrBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Cosa fare quando un controllo non passa. */
fun checkHint(c: Backend.Check): String = when (c.id) {
    "tailscale" -> "Nella pagina di Vigilo premi «Genera link di accesso», apri il link e autorizza il dispositivo."
    "funnel" -> "Nel pannello Tailscale abilita i certificati HTTPS e l'attributo «funnel» nella policy (vedi README)."
    "mqtt" -> "Con Home Assistant: installa e avvia «Mosquitto broker», poi riavvia Vigilo. Con Docker: riavvia il container."
    "telemetry_server" -> "Riavvia Vigilo (componente aggiuntivo o container Docker); se persiste, guarda il registro."
    "credentials" -> "Incolla Client ID e Client Secret dell'app creata su developer.tesla.com."
    "login" -> "Premi «Accedi con Tesla» nella pagina di Vigilo."
    "pubkey" -> "La chiave pubblica non è raggiungibile da internet: controlla che il Funnel sia attivo."
    "partner" -> "Nella pagina di Vigilo premi «Riprova la configurazione» per registrare il dominio presso Tesla."
    "vehicle" -> "Scegli l'auto nella pagina di Vigilo."
    "key" -> "Apri il link della chiave virtuale sul telefono con l'app Tesla e conferma l'aggiunta della chiave."
    "config" -> "Premi «Invia di nuovo la configurazione»: l'auto la riceve al prossimo risveglio, senza svegliarla."
    "car" -> "Normale finché l'auto non si sveglia: alla prima guida o apertura invierà i dati."
    else -> c.detail
}

private fun copy(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
