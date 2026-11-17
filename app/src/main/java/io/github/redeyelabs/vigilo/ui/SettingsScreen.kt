package io.github.redeyelabs.vigilo.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import io.github.redeyelabs.vigilo.widget.VigiloWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import io.github.redeyelabs.vigilo.work.BatteryOpt
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: (() -> Unit)? = null, onOpen: (Screen) -> Unit = {}) {
    val p = vm.prefs
    val ctx = LocalContext.current
    val paired by vm.backendMode.collectAsState()
    val backendUrl by vm.backendUrl.collectAsState()
    val haSettings by vm.backendSettings.collectAsState()
    LaunchedEffect(paired) { if (paired) vm.loadBackendSettings() }

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
            SettingsGroup("Notifiche dall'app") {
                var notifyFromApp by remember { mutableStateOf(p.notifyAlarmsFromApp) }
                Toggle("Notifiche allarme anche dall'app", p.notifyAlarmsFromApp) {
                    notifyFromApp = it
                    p.notifyAlarmsFromApp = it
                    vm.applyMonitoring()
                    if (it && !BatteryOpt.isExempt(ctx)) BatteryOpt.request(ctx)
                }
                Hint("Gli avvisi Aware e Panic arrivano anche da Vigilo, con circa 15–20 secondi di ritardo. Resta accesa una notifica fissa e si consuma un po' più di batteria.")
                if (notifyFromApp) {
                    var exempt by remember { mutableStateOf(BatteryOpt.isExempt(ctx)) }
                    LaunchedEffect(Unit) { while (true) { exempt = BatteryOpt.isExempt(ctx); kotlinx.coroutines.delay(2_000) } }
                    if (!exempt) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Il risparmio batteria può fermare Vigilo in background. Consenti l'uso senza limiti.",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { BatteryOpt.request(ctx) }) { Text("Consenti") }
                        }
                    }
                    Toggle("Suona anche in silenzioso", p.alarmBypassSilent) { p.alarmBypassSilent = it }
                    Hint("Allarmi con il volume della sveglia, anche con il telefono in silenzioso: la sirena dura qualche secondo.")
                }
            }
        }

        SettingsGroup("Suoni") { SoundTests() }

        SettingsGroup("Schermata principale") {
            CarIdentitySettings(p)
            var showHero by remember { mutableStateOf(p.showHeroCar) }
            Toggle("Mostra la foto dell'auto", showHero) { showHero = it; p.showHeroCar = it }
            Hint("Spenta di default, così restano in evidenza la Sentinella e gli avvisi.")
        }

        SettingsGroup("Crediti") {
            Hint("Foto dell'auto predefinita: Model 3 Highland Pearl White Multi-Coat di Ethan Llamas, Wikimedia Commons, licenza CC BY-SA 4.0.")
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://commons.wikimedia.org/wiki/File:Tesla_Model_3_Highland_Performance_AWD_Pearl_White_Multi-Coat.jpg"
                )))
            }) { Text("Pagina Wikimedia Commons") }
            Hint("Vigilo è un progetto open source indipendente, non affiliato a Tesla. Mappe © OpenStreetMap contributors.")
            TextButton(onClick = { onOpen(Screen.LICENSES) }) { Text("Licenze open source") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Crafted with ❤️ by Saverio",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

/** Gruppo di impostazioni nello stesso pannello a contrasto della scheda «Server Vigilo». */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    VigiloCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CarIdentitySettings(p: io.github.redeyelabs.vigilo.data.Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(p.vehicleNameCustom) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40); p.vehicleNameCustom = name; scope.launch { runCatching { VigiloWidget.refresh(ctx) } } },
        label = { Text("Nome dell'auto") },
        placeholder = { Text(p.vehicleName.ifBlank { "Come nell'app Tesla" }) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Lascia vuoto per usare il nome dell'app Tesla. Compare in alto, nel widget e nelle notifiche.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var hasPhoto by remember { mutableStateOf(CarPhoto.hasCustom(ctx)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { CarPhoto.import(ctx, uri) }
            if (ok) { hasPhoto = true; p.showHeroCar = true }
            Toast.makeText(ctx, if (ok) "Foto dell'auto aggiornata." else "Non riesco a leggere questa foto.", Toast.LENGTH_SHORT).show()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text(if (hasPhoto) "Cambia foto" else "Scegli foto dalla galleria") }
        if (hasPhoto) TextButton(onClick = { CarPhoto.reset(ctx); hasPhoto = false }) { Text("Ripristina foto predefinita") }
    }
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
