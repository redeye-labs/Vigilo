package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend

/** Scheda "Home Assistant" in Impostazioni: abbinamento e avvisi gestiti dal componente aggiuntivo. */
@Composable
fun HomeAssistantSettings(
    paired: Boolean,
    backendUrl: String,
    settings: Backend.SettingsResponse?,
    onGuide: () -> Unit,
    onUnpair: () -> Unit,
    onChange: ((Backend.Settings) -> Backend.Settings) -> Unit,
) {
    VigiloCard {
        Text("Server Vigilo", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (!paired) {
            Text(
                "Consigliato. Il server Vigilo (componente aggiuntivo di Home Assistant o versione Docker) riceve gli eventi della Sentinella in tempo reale e li manda al telefono, anche con l'app chiusa.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGuide, modifier = Modifier.fillMaxWidth()) { Text("Configura con la guida") }
            return@VigiloCard
        }
        Text("Abbinato", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(backendUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGuide) { Text("Guida e controlli") }
            TextButton(onClick = onUnpair) { Text("Scollega") }
        }
        Spacer(Modifier.height(8.dp))
        HaAlertSettings(settings, onChange)
    }
}

/** Avvisi Sentinella gestiti da Home Assistant: on/off e pausa tra due avvisi "Aware". */
@Composable
fun HaAlertSettings(settings: Backend.SettingsResponse?, onChange: ((Backend.Settings) -> Backend.Settings) -> Unit) {
    if (settings == null) {
        Text("Lettura delle impostazioni dal server Vigilo…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val s = settings.settings
    Column {
        // key(): se le impostazioni arrivano o cambiano dal server, i controlli ripartono dal valore nuovo.
        key(s) {
            PrefToggle("Avviso: qualcuno vicino all'auto", s.notifyAware) { v -> onChange { it.copy(notifyAware = v) } }
            PrefStepper("Pausa minima tra due avvisi (secondi)", s.awareThrottleSec, 0, 900, 30) { v -> onChange { it.copy(awareThrottleSec = v) } }
            PrefToggle("Avviso: allarme (suona come sveglia)", s.notifyPanic) { v -> onChange { it.copy(notifyPanic = v) } }
            PrefToggle("Avviso se il controllo giornaliero fallisce", s.notifyProblems) { v -> onChange { it.copy(notifyProblems = v) } }
        }
        Text(
            if (s.notifyTargets.isEmpty()) "Nessun telefono di Home Assistant scelto (passo «Notifiche» del componente aggiuntivo). Con la versione Docker gli avvisi arrivano dall'app Vigilo (Impostazioni › Notifiche dall'app) o da ntfy."
            else "Destinatari: " + s.notifyTargets.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
