package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.FleetTelemetrySupport
import kotlin.math.roundToInt

@Composable
fun NotificationsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = vm.prefs
    val savedUrl by vm.telemetryJwsUrl.collectAsState()
    val exp by vm.telemetryExp.collectAsState()
    val autoRenew by vm.telemetryAutoRenew.collectAsState()
    val busy by vm.telemetryBusy.collectAsState()
    val progress by vm.telemetryProgress.collectAsState()
    val status by vm.telemetryMessage.collectAsState()
    // Il campo è la sola fonte di verità mentre si scrive: niente LaunchedEffect che lo sovrascrive.
    var url by remember { mutableStateOf(savedUrl.ifBlank { FleetTelemetrySupport.defaultJwsUrl(p.proxyUrl) }) }
    val paired by vm.backendMode.collectAsState()
    val haSettings by vm.backendSettings.collectAsState()
    LaunchedEffect(paired) { if (paired) vm.loadBackendSettings() else vm.refreshTelemetryExpiry() }

    NotificationsScreenContent(
        url = url,
        onUrlChange = { url = it; vm.commitTelemetryJwsUrl(it) },
        expEpochSeconds = exp,
        autoRenew = autoRenew,
        onAutoRenewChange = { vm.setTelemetryAutoRenew(it) },
        busy = busy,
        progress = progress,
        status = status,
        onActivate = { vm.commitTelemetryJwsUrl(url); vm.activateSentryAlerts() },
        onCheckStatus = { vm.commitTelemetryJwsUrl(url); vm.checkTelemetryStatus() },
        onBack = onBack,
        alertUnlocked = p.alertUnlocked,
        onAlertUnlocked = { p.alertUnlocked = it },
        unlockedMinutes = p.unlockedMinutes,
        onUnlockedMinutes = { p.unlockedMinutes = it },
        alertWindows = p.alertWindows,
        onAlertWindows = { p.alertWindows = it },
        alertChargeComplete = p.alertChargeComplete,
        onAlertChargeComplete = { p.alertChargeComplete = it },
        alertLowBattery = p.alertLowBattery,
        onAlertLowBattery = { p.alertLowBattery = it },
        lowBatteryPct = p.lowBatteryPct,
        onLowBatteryPct = { p.lowBatteryPct = it },
        haCard = if (paired) ({
            VigiloCard {
                Text("Avvisi Sentinella da Home Assistant", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Arrivano in tempo reale tramite l'app Home Assistant del telefono. La configurazione dell'auto la rinnova il componente aggiuntivo, senza svegliarla.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                HaAlertSettings(haSettings) { vm.saveBackendSettings(it) }
            }
        }) else null,
    )
}

@Composable
fun NotificationsScreenContent(
    url: String,
    onUrlChange: (String) -> Unit,
    expEpochSeconds: Long?,
    autoRenew: Boolean,
    onAutoRenewChange: (Boolean) -> Unit,
    busy: Boolean,
    progress: String?,
    status: String?,
    onActivate: () -> Unit,
    onCheckStatus: () -> Unit,
    onBack: () -> Unit,
    alertUnlocked: Boolean,
    onAlertUnlocked: (Boolean) -> Unit = {},
    unlockedMinutes: Int,
    onUnlockedMinutes: (Int) -> Unit = {},
    alertWindows: Boolean,
    onAlertWindows: (Boolean) -> Unit = {},
    alertChargeComplete: Boolean,
    onAlertChargeComplete: (Boolean) -> Unit = {},
    alertLowBattery: Boolean,
    onAlertLowBattery: (Boolean) -> Unit = {},
    lowBatteryPct: Int,
    onLowBatteryPct: (Int) -> Unit = {},
    haCard: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        ScreenHeader(
            "Notifiche",
            "Scegli quali avvisi ricevere quando Vigilo controlla l'auto.",
            onBack = onBack,
        )

        if (haCard != null) haCard() else SentryAlertsCard(
            url = url,
            onUrlChange = onUrlChange,
            expEpochSeconds = expEpochSeconds,
            autoRenew = autoRenew,
            onAutoRenewChange = onAutoRenewChange,
            busy = busy,
            progress = progress,
            status = status,
            onActivate = onActivate,
            onCheckStatus = onCheckStatus,
        )

        Spacer(Modifier.height(16.dp))
        Text("Avvisi dal controllo periodico", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Partono quando l'app legge lo stato dell'auto, non nell'istante dell'evento.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        PrefToggle("Auto lasciata aperta", alertUnlocked, onAlertUnlocked)
        PrefStepper("…dopo (minuti)", unlockedMinutes, 1, 60, 1, onUnlockedMinutes)
        PrefToggle("Finestrino aperto", alertWindows, onAlertWindows)
        PrefToggle("Ricarica completata", alertChargeComplete, onAlertChargeComplete)
        PrefToggle("Batteria bassa", alertLowBattery, onAlertLowBattery)
        PrefStepper("Soglia batteria bassa (%)", lowBatteryPct, 5, 50, 5, onLowBatteryPct)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SentryAlertsCard(
    url: String,
    onUrlChange: (String) -> Unit,
    expEpochSeconds: Long?,
    autoRenew: Boolean,
    onAutoRenewChange: (Boolean) -> Unit,
    busy: Boolean,
    progress: String?,
    status: String?,
    onActivate: () -> Unit,
    onCheckStatus: () -> Unit,
) {
    VigiloCard {
        Text("Attiva avvisi Sentinella", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "L'auto invia subito gli eventi della Sentinella al tuo Home Assistant, senza aspettare il controllo periodico. Serve la chiave virtuale già associata e Home Assistant raggiungibile da Tailscale.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (expEpochSeconds != null && expEpochSeconds > 0)
                "Scade il ${FleetTelemetrySupport.formatExp(expEpochSeconds)}"
            else
                "Scadenza della configurazione: non ancora letta.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("Indirizzo del file di configurazione") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onActivate, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.size(8.dp))
            }
            Text("Attiva avvisi Sentinella")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCheckStatus, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Controlla stato")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rinnovo automatico", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            VigiloSwitch(checked = autoRenew, onCheckedChange = onAutoRenewChange, enabled = !busy)
        }
        Text(
            "Una volta al giorno, in carica o su Wi-Fi, Vigilo aggiorna la configurazione se è cambiata o se l'auto non è sincronizzata.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!progress.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!status.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ActionsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = vm.prefs
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        ScreenHeader(
            "Azioni automatiche",
            "Cosa fa l'auto se, al controllo, risulta un evento con la Sentinella attiva.",
            onBack = onBack,
        )
        PrefToggle("Lampeggia i fari automaticamente", p.autoFlash) { p.autoFlash = it }
        PrefToggle("Suona il clacson automaticamente", p.autoHonk) { p.autoHonk = it }
        PrefStepper("Spostamento considerato sospetto (metri)", p.movementMeters, 20, 500, 10) { p.movementMeters = it }
        Spacer(Modifier.height(8.dp))
        Text(
            "Queste azioni partono dopo un controllo dello stato, non in tempo reale. Gli avvisi immediati della Sentinella si attivano dalla scheda Notifiche.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PrefToggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        VigiloSwitch(v, { v = it; onChange(it) })
    }
}

@Composable
fun PrefStepper(label: String, initial: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(initial.toFloat()) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("$label: ${v.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
        VigiloSlider(
            value = v,
            onValueChange = { v = ((it / step).roundToInt() * step).toFloat().coerceIn(min.toFloat(), max.toFloat()) },
            valueRange = min.toFloat()..max.toFloat(),
            onValueChangeFinished = { onChange(v.roundToInt()) },
        )
    }
}
