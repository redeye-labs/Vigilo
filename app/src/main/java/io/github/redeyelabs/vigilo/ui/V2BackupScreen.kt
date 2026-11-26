package io.github.redeyelabs.vigilo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backup of non-secret V2 data, never a copy of Tesla credentials. */
@Composable
fun V2BackupScreen(vm: MainViewModel, onBack: () -> Unit) {
    val paired by vm.backendMode.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var working by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val content = pendingJson
        pendingJson = null
        if (uri != null && content != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(content.toByteArray(Charsets.UTF_8))
                        } ?: error("Destinazione non scrivibile")
                    }
                    message = "Esportazione salvata."
                } catch (e: Exception) {
                    message = e.message ?: "Esportazione non salvata."
                }
            }
        }
    }

    val saveCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val content = pendingCsv
        pendingCsv = null
        if (uri != null && content != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use {
                            it.write(content.toByteArray(Charsets.UTF_8))
                        } ?: error("Destinazione non scrivibile")
                    }
                    message = "CSV salvato."
                } catch (e: Exception) {
                    message = e.message ?: "Impossibile salvare CSV."
                }
            }
        }
    }

    LaunchedEffect(paired, refresh) {
        if (!paired) return@LaunchedEffect
        working = true
        try {
            enabled = withContext(Dispatchers.IO) {
                Backend(vm.prefs).v2Capabilities().modules.any { it.id == "backup" && it.enabled }
            }
        } catch (e: Exception) {
            message = e.message ?: "Server Vigilo non raggiungibile."
        } finally { working = false }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Backup e report", "Esporta i dati raccolti dal tuo server.", onBack = onBack)
        VigiloCard {
            Text("Esportazione portabile", style = MaterialTheme.typography.titleMedium)
            Text(
                "Il file include eventi e misure Vigilo 2 senza VIN, credenziali Tesla, token o messaggi personali. " +
                    "Lo storico può essere parziale. È una copia dei dati, non un backup completo dell'installazione.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!paired) {
                Text("Collega prima il server Vigilo.")
            } else {
                OutlinedButton(
                    enabled = !working,
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    Backend(vm.prefs).v2SetModule("backup", !enabled)
                                }
                                refresh++
                            } catch (e: Exception) {
                                message = e.message ?: "Impossibile cambiare il modulo."
                            } finally { working = false }
                        }
                    },
                ) { Text(if (enabled) "Disattiva esportazione" else "Attiva esportazione") }
                OutlinedButton(
                    enabled = enabled && !working,
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                pendingJson = withContext(Dispatchers.IO) {
                                    Backend(vm.prefs).v2BackupExportJson()
                                }
                                saveBackup.launch("Vigilo2-dati-${LocalDate.now()}.json")
                            } catch (e: Exception) {
                                message = e.message ?: "Impossibile esportare i dati."
                            } finally { working = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Esporta dati JSON") }
            }
        }
        VigiloCard {
            Text("Report sicurezza", style = MaterialTheme.typography.titleMedium)
            Text(
                "Scarica gli eventi senza titoli e messaggi potenzialmente sensibili. " +
                    "Il file CSV contiene solo ID, orario e categoria degli eventi del server attivo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = paired && !working,
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            pendingCsv = withContext(Dispatchers.IO) {
                                Backend(vm.prefs).v2SecurityCsv()
                            }
                            saveCsv.launch("Vigilo-sicurezza-${LocalDate.now()}.csv")
                        } catch (e: Exception) {
                            message = e.message ?: "Impossibile esportare CSV."
                        } finally { working = false }
                    }
                },
            ) { Text("Esporta eventi CSV") }
        }
        VigiloCard {
            Text("Backup completo del server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Per salvare anche chiavi, connessione Tesla e configurazione, " +
                    "usa il backup dell'add-on Home Assistant oppure una copia protetta del volume /data Docker. " +
                    "Non caricare mai questi file su GitHub o in servizi pubblici.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { VigiloCard { Text(it) } }
    }
}
