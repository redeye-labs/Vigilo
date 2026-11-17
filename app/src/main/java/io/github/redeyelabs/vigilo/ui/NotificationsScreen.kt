package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend
import kotlin.math.roundToInt

/** Avvisi Sentinella: li invia il server Vigilo (componente aggiuntivo di Home Assistant o Docker). */
@Composable
fun NotificationsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val paired by vm.backendMode.collectAsState()
    val haSettings by vm.backendSettings.collectAsState()
    LaunchedEffect(paired) { if (paired) vm.loadBackendSettings() }
    NotificationsScreenContent(
        paired = paired,
        settings = haSettings,
        onChange = { vm.saveBackendSettings(it) },
        onBack = onBack,
    )
}

@Composable
fun NotificationsScreenContent(
    paired: Boolean,
    settings: Backend.SettingsResponse?,
    onChange: ((Backend.Settings) -> Backend.Settings) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        ScreenHeader("Notifiche", "Avvisi della Sentinella inviati dal server Vigilo.", onBack = onBack)
        VigiloCard {
            Text("Avvisi Sentinella dal server Vigilo", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Con Home Assistant arrivano in tempo reale tramite la sua app; con la versione Docker dall'app Vigilo (Impostazioni › Notifiche dall'app) o da ntfy. La configurazione dell'auto la rinnova il server Vigilo, senza svegliarla.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (paired) HaAlertSettings(settings, onChange)
            else Text(
                "Collega prima l'app al server Vigilo dalla Guida.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Per riceverle anche dall'app Vigilo, con suono, apri Impostazioni e attiva «Notifiche allarme anche dall'app».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
