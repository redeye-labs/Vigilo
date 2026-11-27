package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
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
                    val status = when (d.telemetry.status) {
                        "ok" -> "Aggiornata"
                        "vehicle_asleep" -> "Auto addormentata"
                        "telemetry_stale" -> "Dati non recenti"
                        "server_offline" -> "Server offline"
                        "missing_permission" -> "Permessi mancanti"
                        else -> "Non disponibile"
                    }
                    MetricPair("Telemetria", status, "Server", if (d.serverReachable) "Raggiungibile" else "Offline", MaterialTheme.colorScheme.onSurface)
                    Text("Permessi Tesla: " + if (d.permissionStatus == "not_verified") "Non verificati" else d.permissionStatus)
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
                    TireLayout(tires)
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

@Composable
private fun TireLayout(tires: List<Backend.V2Measurement>) {
    val accent = MaterialTheme.colorScheme.onSurface
    fun reading(signal: String): String {
        val m = tires.firstOrNull { it.signal == signal } ?: return "—"
        return String.format(Locale.ITALY, "%.2f bar", m.value) + if (m.quality == "stale") " *" else ""
    }
    Row(Modifier.fillMaxWidth().height(220.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            TireReading("Anteriore SX", reading("TpmsPressureFl"), accent)
            TireReading("Posteriore SX", reading("TpmsPressureRl"), accent)
        }
        Canvas(Modifier.weight(0.9f).height(200.dp)) {
            val w = size.width
            val h = size.height
            for (x in listOf(.04f, .76f)) for (y in listOf(.18f, .68f)) {
                drawRoundRect(Color(0xFF454545), Offset(w * x, h * y), Size(w * .20f, h * .17f), CornerRadius(5.dp.toPx()))
            }
            drawRoundRect(Color(0xFFB5B5B5), Offset(w * .17f, h * .02f), Size(w * .66f, h * .96f), CornerRadius(24.dp.toPx()))
            drawRoundRect(Color(0xFF252525), Offset(w * .25f, h * .22f), Size(w * .50f, h * .53f), CornerRadius(14.dp.toPx()))
            drawRoundRect(Color(0xFF444444), Offset(w * .29f, h * .28f), Size(w * .42f, h * .14f), CornerRadius(5.dp.toPx()))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            TireReading("Anteriore DX", reading("TpmsPressureFr"), accent)
            TireReading("Posteriore DX", reading("TpmsPressureRr"), accent)
        }
    }
    if (tires.any { it.quality == "stale" }) Text("* Lettura non recente", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TireReading(label: String, value: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = if (value == "—" || value.endsWith("*")) MaterialTheme.colorScheme.onSurfaceVariant else accent)
    }
}
