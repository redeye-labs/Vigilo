package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vigilo 2 preview. Never substitutes demo values for missing telemetry. */
@Composable
fun V2HubScreen(vm: MainViewModel, onBack: () -> Unit) {
    val paired by vm.backendMode.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf<Backend.V2Capabilities?>(null) }
    var diagnostic by remember { mutableStateOf<Backend.V2Diagnostics?>(null) }
    var security by remember { mutableStateOf<Backend.V2Security?>(null) }
    var report by remember { mutableStateOf<Backend.V2Report?>(null) }

    LaunchedEffect(paired, refresh) {
        capabilities = null
        diagnostic = null
        security = null
        report = null
        error = null
        loading = paired
        if (paired) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val b = Backend(vm.prefs)
                    Pair(Triple(b.v2Capabilities(), b.v2Diagnostics(), b.v2Security()), b.v2SecurityReport())
                }
                capabilities = result.first.first
                diagnostic = result.first.second
                security = result.first.third
                report = result.second
            } catch (e: Exception) {
                error = if (Backend.isUnsupported(e)) {
                    "Il server Vigilo non supporta ancora queste funzionalità. Non è necessario cambiare l'app attuale."
                } else {
                    e.message ?: "Impossibile leggere la diagnostica del server."
                }
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Vigilo 2", "Anteprima dei moduli sul server personale.", onBack = onBack)
        VigiloCard {
            Text("Versione sperimentale", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Nessuna funzione aggiuntiva viene attivata automaticamente. " +
                    "I comandi Tesla sensibili rimangono disabilitati.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!paired) {
            VigiloCard { Text("Collega prima Vigilo al server dalla Guida.") }
        } else if (loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            VigiloCard {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { refresh++ }) { Text("Riprova") }
            }
        } else {
            diagnostic?.let { d ->
                VigiloCard {
                    Text("Diagnostica", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Telemetria: " + when (d.telemetry.status) {
                        "ok" -> "Aggiornata"
                        "vehicle_asleep" -> "Auto addormentata"
                        "telemetry_stale" -> "Dati non recenti"
                        "server_offline" -> "Server non raggiungibile"
                        "missing_permission" -> "Permessi mancanti"
                        else -> "Nessun dato recente"
                    })
                    Text(d.telemetry.details, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Permessi Tesla: " + if (d.permissionStatus == "not_verified") "Non ancora verificati" else d.permissionStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            security?.let { s ->
                VigiloCard {
                    Text("Sicurezza intelligente", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("${s.incidents.size} episodi raggruppati nello storico disponibile")
                    val alarms = s.incidents.count { it.panic }
                    Text("$alarms episodi con allarme Panic")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "I conteggi dipendono dallo storico presente sul server. " +
                            "Non rappresentano necessariamente tutti gli eventi avvenuti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            report?.let { r ->
                VigiloCard {
                    Text("Report degli ultimi 7 giorni", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("${r.totalEvents} eventi registrati")
                    Text("${r.awareEvents} eventi Aware · ${r.panicEvents} allarmi Panic")
                    Text("${r.groupedIncidents} episodi raggruppati")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Copertura parziale: eventi disponibili nell'archivio locale del server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            capabilities?.let { c ->
                VigiloCard {
                    Text("Moduli", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val names = mapOf(
                        "security" to "Sicurezza", "diagnostics" to "Diagnostica",
                        "backup" to "Backup e report", "commands" to "Comandi avanzati",
                        "battery" to "Salute batteria", "charging" to "Ricariche e costi",
                        "automation" to "Automazioni", "trips" to "Viaggi e consumi",
                    )
                    c.modules.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(names[m.id] ?: m.id, Modifier.weight(1f))
                            Text(
                                if (m.available) "Anteprima" else "In sviluppo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Aggiorna dati")
            }
        }
    }
}
