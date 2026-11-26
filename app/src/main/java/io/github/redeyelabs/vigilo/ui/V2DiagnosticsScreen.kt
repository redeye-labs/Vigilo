package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun V2DiagnosticsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val paired by vm.backendMode.collectAsState()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<Backend.V2Diagnostics?>(null) }
    var tires by remember { mutableStateOf<List<Backend.V2Measurement>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(paired, refresh) {
        if (!paired) return@LaunchedEffect
        working = true
        message = null
        try {
            val result = withContext(Dispatchers.IO) {
                val backend = Backend(vm.prefs)
                val caps = backend.v2Capabilities().modules
                val active = caps.any { it.id == "diagnostics" && it.enabled }
                Triple(active, backend.v2Diagnostics(),
                    if (active) backend.v2TireSamples() else emptyList())
            }
            enabled = result.first
            diagnostics = result.second
            tires = result.third
        } catch (e: Exception) {
            message = e.message ?: "Diagnostica non disponibile."
        } finally { working = false }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Diagnostica Vigilo", "Connessione e segnali ricevuti dalla Tesla.", onBack = onBack)
        if (!paired) {
            VigiloCard { Text("Collega prima il tuo server Vigilo.") }
        } else {
            diagnostics?.let { d ->
                VigiloCard {
                    Text("Connessione", style = MaterialTheme.typography.titleMedium)
                    Text(d.telemetry.details)
                    Text("Stato: ${d.telemetry.status}")
                    Text("Permessi Tesla: ${d.permissionStatus}")
                    Text(
                        "Un'auto addormentata non equivale a un errore. L'assenza di un segnale può dipendere dal firmware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            VigiloCard {
                Text("Pressione pneumatici", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Letture effettive Tesla in bar, se supportate dal veicolo. Valori non aggiornati non costituiscono una diagnosi di guasto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    enabled = !working,
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    Backend(vm.prefs).v2SetModule("diagnostics", !enabled)
                                }
                                refresh++
                            } catch (e: Exception) {
                                message = e.message ?: "Impossibile attivare la diagnostica."
                            } finally { working = false }
                        }
                    },
                ) { Text(if (enabled) "Disattiva raccolta pneumatici" else "Attiva raccolta pneumatici") }
                if (enabled) {
                    val names = mapOf(
                        "TpmsPressureFl" to "Anteriore sinistra",
                        "TpmsPressureFr" to "Anteriore destra",
                        "TpmsPressureRl" to "Posteriore sinistra",
                        "TpmsPressureRr" to "Posteriore destra",
                    )
                    for (name in names.keys) {
                        val measure = tires.firstOrNull { it.signal == name }
                        Text("${names[name]}: " +
                            (measure?.let { String.format(Locale.ITALY, "%.2f bar", it.value) } ?: "Dato non disponibile"))
                    }
                    if (tires.isNotEmpty()) {
                        Text(
                            "Ultimo aggiornamento: ${tires.maxByOrNull { it.receivedAt }?.receivedAt.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            message?.let { VigiloCard { Text(it, color = MaterialTheme.colorScheme.error) } }
            OutlinedButton(
                onClick = { refresh++ }, enabled = !working, modifier = Modifier.fillMaxWidth(),
            ) { Text("Aggiorna diagnostica") }
        }
    }
}
