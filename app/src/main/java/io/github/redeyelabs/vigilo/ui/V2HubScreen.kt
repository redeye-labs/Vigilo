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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Vigilo 2 preview. Never substitutes demo values for missing telemetry. */
@Composable
fun V2HubScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val paired by vm.backendMode.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf<Backend.V2Capabilities?>(null) }
    var diagnostic by remember { mutableStateOf<Backend.V2Diagnostics?>(null) }
    var security by remember { mutableStateOf<Backend.V2Security?>(null) }
    var report by remember { mutableStateOf<Backend.V2Report?>(null) }
    var batterySamples by remember { mutableStateOf<List<Backend.V2Measurement>>(emptyList()) }
    var batteryBusy by remember { mutableStateOf(false) }
    var batteryMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var confirmCommand by remember { mutableStateOf<String?>(null) }
    var commandBusy by remember { mutableStateOf(false) }
    var commandMessage by remember { mutableStateOf<String?>(null) }


    LaunchedEffect(paired, refresh) {
        capabilities = null
        diagnostic = null
        security = null
        report = null
        batterySamples = emptyList()
        error = null
        loading = paired
        if (paired) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val b = Backend(vm.prefs)
                    val caps = b.v2Capabilities()
                    val samples = if (caps.modules.any { it.id == "battery" && it.enabled }) {
                        b.v2BatterySamples()
                    } else emptyList()
                    Pair(Triple(caps, b.v2Diagnostics(), b.v2Security()), Pair(b.v2SecurityReport(), samples))
                }
                capabilities = result.first.first
                diagnostic = result.first.second
                security = result.first.third
                report = result.second.first
                batterySamples = result.second.second
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
            capabilities?.modules?.firstOrNull { it.id == "battery" }?.let { batteryModule ->
                VigiloCard {
                    Text("Batteria e autonomia", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Solo misure ricevute da Tesla. La salute batteria non viene stimata senza un riferimento BMS confrontabile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = !batteryBusy,
                        onClick = {
                            batteryBusy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        Backend(vm.prefs).v2SetModule("battery", !batteryModule.enabled)
                                    }
                                    refresh++
                                } catch (e: Exception) {
                                    batteryMessage = e.message ?: "Impossibile cambiare il modulo batteria."
                                } finally {
                                    batteryBusy = false
                                }
                            }
                        },
                    ) { Text(if (batteryModule.enabled) "Disattiva raccolta" else "Attiva raccolta batteria") }
                    if (batteryModule.enabled) {
                        if (batterySamples.isEmpty()) {
                            Text("Nessuna misura disponibile: Vigilo non inventa valori mancanti.")
                        } else {
                            val labels = mapOf(
                                "Soc" to "Stato di carica",
                                "RatedRange" to "Autonomia nominale",
                                "NominalFullPackEnergyKwh" to "Capacità nominale BMS",
                            )
                            batterySamples.forEach { sample ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                    Text(labels[sample.signal] ?: sample.signal, Modifier.weight(1f))
                                    Text("${"%.1f".format(sample.value)} ${sample.unit}")
                                }
                            }
                        }
                    }
                    batteryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            capabilities?.modules?.firstOrNull { it.id == "commands" }?.let { commandModule ->
                VigiloCard {
                    Text("Comandi Tesla avanzati", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Le azioni sono manuali, richiedono conferma e non svegliano automaticamente l'auto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            commandBusy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        Backend(vm.prefs).v2SetModule("commands", !commandModule.enabled)
                                    }
                                    refresh++
                                } catch (e: Exception) {
                                    commandMessage = e.message ?: "Impossibile modificare il modulo."
                                } finally {
                                    commandBusy = false
                                }
                            }
                        },
                        enabled = !commandBusy,
                    ) { Text(if (commandModule.enabled) "Disattiva comandi" else "Abilita comandi") }
                    if (commandModule.enabled) {
                        val actions = listOf(
                            "climate_on" to "Accendi clima",
                            "climate_off" to "Spegni clima",
                            "charge_start" to "Avvia ricarica",
                            "charge_stop" to "Ferma ricarica",
                            "charge_port_open" to "Apri sportellino",
                            "charge_port_close" to "Chiudi sportellino",
                            "trunk_rear" to "Aziona bagagliaio",
                            "trunk_front" to "Aziona frunk",
                        )
                        actions.forEach { (action, label) ->
                            OutlinedButton(
                                onClick = { confirmCommand = action },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !commandBusy,
                            ) { Text(label) }
                        }
                    }
                    commandMessage?.let { text ->
                        Spacer(Modifier.height(6.dp))
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Aggiorna dati")
            }
        }
    }

    confirmCommand?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!commandBusy) confirmCommand = null },
            title = { Text("Conferma comando Tesla") },
            text = {
                Text("Vuoi inviare \"$action\" all'auto? Il veicolo deve essere online e alcuni comandi possono azionare parti fisiche.")
            },
            confirmButton = {
                TextButton(
                    enabled = !commandBusy,
                    onClick = {
                        commandBusy = true
                        scope.launch {
                            try {
                                commandMessage = withContext(Dispatchers.IO) {
                                    Backend(vm.prefs).v2ManualCommand(action)
                                }
                            } catch (e: Exception) {
                                commandMessage = e.message ?: "Il comando non è stato completato."
                            } finally {
                                commandBusy = false
                                confirmCommand = null
                            }
                        }
                    },
                ) { Text("Conferma") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmCommand = null },
                    enabled = !commandBusy,
                ) { Text("Annulla") }
            },
        )
    }
}
