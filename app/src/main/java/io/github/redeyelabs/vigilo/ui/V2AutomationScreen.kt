package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Notification-only automations: NO autonomous Tesla movement commands. */
@Composable
fun V2AutomationScreen(vm: MainViewModel, onBack: () -> Unit) {
    val paired by vm.backendMode.collectAsState()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var moduleEnabled by remember { mutableStateOf(false) }
    var lowEnabled by remember { mutableStateOf(false) }
    var panicEnabled by remember { mutableStateOf(false) }
    var parkedEnabled by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf("20") }
    var rulesLoaded by remember { mutableStateOf(false) }
    var executions by remember { mutableStateOf(emptyList<Backend.V2RuleExecution>()) }
    var savedLimit by remember { mutableStateOf("20") }

    LaunchedEffect(paired, refresh) {
        if (!paired) return@LaunchedEffect
        working = true
        error = null
        try {
            val data = withContext(Dispatchers.IO) {
                val b = Backend(vm.prefs)
                Pair(b.v2Capabilities(), b.v2Automations())
            }
            moduleEnabled = data.first.modules.any { it.id == "automation" && it.enabled }
            lowEnabled = data.second.rules.firstOrNull { it.id == "soc-low" }?.enabled ?: false
            panicEnabled = data.second.rules.firstOrNull { it.id == "sentry-panic" }?.enabled ?: false
            parkedEnabled = data.second.rules.firstOrNull { it.id == "parked-away" }?.enabled ?: false
            limit = (data.second.rules.firstOrNull { it.id == "soc-low" }?.threshold?.toInt() ?: 20).toString()
            savedLimit = limit
            executions = data.second.executions
            rulesLoaded = true
        } catch (e: Exception) {
            error = e.message ?: "Non riesco a caricare le regole."
        } finally {
            working = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Automazioni Vigilo", "Avvisi personalizzati eseguiti dal server.", onBack = onBack)
        VigiloCard {
            Text("Sicurezza", style = MaterialTheme.typography.titleMedium)
            Text(
                "Le automazioni aggiuntive inviano solo notifiche. Nessuna apertura, guida o comando Tesla automatico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!paired) {
            VigiloCard { Text("Collega prima il server Vigilo.") }
        } else {
            error?.let { VigiloCard { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!rulesLoaded) {
                Text("Caricamento regole…")
            } else {
                VigiloCard {
                    Text("Stato automazioni", style = MaterialTheme.typography.titleMedium)
                    MetricPair("Servizio", if (moduleEnabled) "Attivo" else "Disattivo", "Regole abilitate", listOf(lowEnabled, panicEnabled, parkedEnabled).count { it }.toString() + "/3", MaterialTheme.colorScheme.primary)
                    OutlinedButton(
                        enabled = !working,
                        onClick = {
                            working = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        Backend(vm.prefs).v2SetModule("automation", !moduleEnabled)
                                    }
                                    refresh++
                                } catch (e: Exception) {
                                    error = e.message ?: "Operazione non riuscita."
                                } finally {
                                    working = false
                                }
                            }
                        },
                    ) { Text(if (moduleEnabled) "Disattiva automazioni" else "Attiva automazioni") }
                }
                VigiloCard {
                    Text("Ultimi avvisi attivati", style = MaterialTheme.typography.titleMedium)
                    if (executions.isEmpty()) Text("Nessuna esecuzione registrata.")
                    executions.take(10).forEach { execution ->
                        val label = when (execution.condition) {
                            "soc_below" -> "Batteria sotto soglia"
                            "sentry_panic" -> "Allarme Sentinella"
                            "away_from_home" -> "Parcheggio fuori casa"
                            else -> execution.ruleId
                        }
                        val time = runCatching {
                            val instant = java.time.Instant.parse(execution.at)
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date.from(instant))
                        }.getOrDefault(execution.at)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(time, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                VigiloCard {
                    Text("Batteria sotto soglia", style = MaterialTheme.typography.titleMedium)
                    Text(if (lowEnabled && moduleEnabled) "Attiva" else if (lowEnabled) "Configurata · servizio disattivo" else "Disattiva", style = MaterialTheme.typography.labelMedium, color = if (lowEnabled && moduleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Un solo avviso quando la carica scende sotto la soglia. Reset quando risale almeno di due punti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = limit,
                        onValueChange = { limit = it.filter(Char::isDigit).take(2) },
                        label = { Text("Soglia % (5–60)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        enabled = !working && limit.toIntOrNull()?.let { it in 5..60 } == true,
                        onClick = {
                            working = true
                            scope.launch {
                                try {
                                    val next = !lowEnabled || limit != savedLimit
                                    withContext(Dispatchers.IO) {
                                        Backend(vm.prefs).v2SaveAutomation(Backend.V2AutomationRule(
                                            id = "soc-low", enabled = next, condition = "soc_below",
                                            threshold = limit.toInt().toDouble(), cooldownSec = 3600,
                                        ))
                                    }
                                    refresh++
                                } catch (e: Exception) {
                                    error = e.message ?: "La regola non è stata salvata."
                                } finally { working = false }
                            }
                        },
                    ) { Text(when { !lowEnabled -> "Attiva avviso batteria"; limit != savedLimit -> "Salva soglia"; else -> "Disattiva avviso batteria" }) }
                }
                VigiloCard {
                    Text("Allarme Sentinella", style = MaterialTheme.typography.titleMedium)
                    Text(if (panicEnabled && moduleEnabled) "Attiva" else if (panicEnabled) "Configurata · servizio disattivo" else "Disattiva", style = MaterialTheme.typography.labelMedium, color = if (panicEnabled && moduleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Invia un avviso aggiuntivo in caso di evento Panic, con intervallo minimo di un'ora.",
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
                                        Backend(vm.prefs).v2SaveAutomation(Backend.V2AutomationRule(
                                            id = "sentry-panic", enabled = !panicEnabled,
                                            condition = "sentry_panic", threshold = 0.0, cooldownSec = 3600,
                                        ))
                                    }
                                    refresh++
                                } catch (e: Exception) {
                                    error = e.message ?: "La regola non è stata salvata."
                                } finally { working = false }
                            }
                        },
                    ) { Text(if (panicEnabled) "Disattiva avviso Panic aggiuntivo" else "Attiva avviso Panic aggiuntivo") }
                }
                VigiloCard {
                    Text("Parcheggio fuori casa", style = MaterialTheme.typography.titleMedium)
                    Text(if (parkedEnabled && moduleEnabled) "Attiva" else if (parkedEnabled) "Configurata · servizio disattivo" else "Disattiva", style = MaterialTheme.typography.labelMedium, color = if (parkedEnabled && moduleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Avviso una volta quando la Tesla indica che si trova fuori casa ed è in P. " +
                            "Richiede il segnale LocatedAtHome: potrebbe non essere disponibile su tutti i veicoli.",
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
                                        Backend(vm.prefs).v2SaveAutomation(Backend.V2AutomationRule(
                                            id = "parked-away", enabled = !parkedEnabled,
                                            condition = "away_from_home", threshold = 0.0, cooldownSec = 3600,
                                        ))
                                    }
                                    refresh++
                                } catch (e: Exception) {
                                    error = e.message ?: "La regola non è stata salvata."
                                } finally { working = false }
                            }
                        },
                    ) { Text(if (parkedEnabled) "Disattiva avviso fuori casa" else "Attiva avviso fuori casa") }
                }
                Text(
                    "I comandi e le programmazioni Sentinella già esistenti restano indipendenti.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
