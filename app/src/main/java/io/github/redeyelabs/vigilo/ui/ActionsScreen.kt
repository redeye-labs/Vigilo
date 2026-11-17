package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
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

/** Riassunto per la riga della schermata principale. */
fun actionsSubtitle(state: MainViewModel.ActionsState): String {
    val a = (state as? MainViewModel.ActionsState.Ready)?.actions
    if (a == null || !a.enabled || (!a.flash && !a.honk) || (!a.onAware && !a.onPanic)) return ACTIONS_SUBTITLE
    val what = listOfNotNull("fari".takeIf { a.flash }, "clacson".takeIf { a.honk }).joinToString(" e ")
    val wheN = when {
        a.onAware && a.onPanic -> "allerta e allarme"
        a.onPanic -> "allarme"
        else -> "allerta"
    }
    return "Attive: $what su $wheN"
}

const val ACTIONS_SUBTITLE = "Fari o clacson quando la Sentinella si allerta"

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun ActionsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.actions.collectAsState()
    LaunchedEffect(Unit) { vm.loadActions() }
    ActionsScreenContent(state = state, onChange = vm::saveActions, onRetry = vm::loadActions, onBack = onBack)
}

@Composable
fun ActionsScreenContent(
    state: MainViewModel.ActionsState,
    onChange: ((Backend.Actions) -> Backend.Actions) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        ScreenHeader(
            "Azioni automatiche",
            "Quando la Sentinella rileva qualcuno, l'auto reagisce da sola. Le esegue il server Vigilo.",
            onBack = onBack,
        )
        when (state) {
            MainViewModel.ActionsState.Loading -> Hint("Caricamento…")
            MainViewModel.ActionsState.NotPaired -> SettingsGroup("Server non collegato") {
                Hint("Collega prima l'app al server Vigilo dalla Guida.")
            }
            MainViewModel.ActionsState.Unsupported -> SettingsGroup("Aggiorna il server Vigilo") {
                Hint("Serve il server Vigilo 1.0.7 o successivo. Home Assistant: aggiorna il componente aggiuntivo Vigilo. Docker: avvia «Vigilo – Aggiorna».")
            }
            is MainViewModel.ActionsState.Failed -> SettingsGroup("Server non raggiungibile") {
                Hint(state.message)
                OutlinedButton(onClick = onRetry) { Text("Riprova", color = MaterialTheme.colorScheme.onSurface) }
            }
            is MainViewModel.ActionsState.Ready -> ActionsForm(state.actions, onChange)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionsForm(a: Backend.Actions, onChange: ((Backend.Actions) -> Backend.Actions) -> Unit) {
    val on = a.enabled
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VigiloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Azioni automatiche", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (on) "Attive" else "Spente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VigiloSwitch(checked = on, onCheckedChange = { v -> onChange { it.copy(enabled = v) } })
            }
        }
        SettingsGroup("Quando") {
            Switch("Qualcuno vicino all'auto", a.onAware, on) { v -> onChange { it.copy(onAware = v) } }
            Switch("Allarme in corso", a.onPanic, on) { v -> onChange { it.copy(onPanic = v) } }
        }
        SettingsGroup("Cosa fa l'auto") {
            Switch("Lampeggia i fari", a.flash, on) { v -> onChange { it.copy(flash = v) } }
            Switch("Suona il clacson", a.honk, on) { v -> onChange { it.copy(honk = v) } }
            Hint("Il clacson si sente in tutta la via: usalo con criterio.")
        }
        SettingsGroup("Limiti") {
            Cooldown(a.cooldownSec, on) { v -> onChange { it.copy(cooldownSec = v) } }
            Switch("Solo in una fascia oraria", a.hoursOnly, on) { v -> onChange { it.copy(hoursOnly = v) } }
            if (a.hoursOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeButton("Dalle", a.fromMin, on) { v -> onChange { it.copy(fromMin = v) } }
                    Spacer(Modifier.width(12.dp))
                    TimeButton("Alle", a.toMin, on) { v -> onChange { it.copy(toMin = v) } }
                }
            }
        }
        Hint("Ogni azione eseguita compare nello Storico.")
    }
}

@Composable
private fun Switch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
        )
        VigiloSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Cooldown(sec: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var v by remember(sec) { mutableFloatStateOf((sec / 60f).coerceIn(1f, 60f)) }
    Column {
        Text(
            "Pausa tra due reazioni: ${v.roundToInt()} min",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
        )
        VigiloSlider(
            value = v,
            onValueChange = { v = it.roundToInt().toFloat() },
            valueRange = 1f..60f,
            enabled = enabled,
            onValueChangeFinished = { onChange(v.roundToInt() * 60) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeButton(label: String, minutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled) {
        Text("$label ${hm(minutes)}", color = MaterialTheme.colorScheme.onSurface)
    }
    if (open) {
        val st = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(label) },
            text = {
                TimeInput(
                    state = st,
                    colors = TimePickerDefaults.colors(
                        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onChange(st.hour * 60 + st.minute); open = false }) {
                    Text("Salva", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Annulla", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
}
