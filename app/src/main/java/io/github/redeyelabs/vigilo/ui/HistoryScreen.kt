package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.data.EventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: MainViewModel, onBack: (() -> Unit)? = null) {
    val events by vm.events.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.syncEvents() }
    var onlyAlerts by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("EEE dd/MM HH:mm", Locale.ITALY) }
    val shown = if (onlyAlerts) events.filter { EventType.isAlert(it.type) } else events
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        selectedLabelColor = MaterialTheme.colorScheme.primary,
    )

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        ScreenHeader("Storico", "Eventi e avvisi: dal telefono e, se abbinato, da Home Assistant.", onBack = onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            FilterChip(selected = !onlyAlerts, onClick = { onlyAlerts = false }, label = { Text("Tutto") }, colors = chipColors)
            FilterChip(selected = onlyAlerts, onClick = { onlyAlerts = true }, label = { Text("Solo allarmi") }, colors = chipColors)
            TextButton(onClick = { confirm = true }) { Text("Svuota") }
        }
        if (shown.isEmpty()) Text("Nessun evento registrato.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { e ->
                val alarm = EventType.isAlert(e.type)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (alarm) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium)
                        Text(e.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            fmt.format(Date(e.timestamp)) + (e.latitude?.let { " · %.5f, %.5f".format(Locale.US, it, e.longitude) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); confirm = false }) { Text("Svuota") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } },
        title = { Text("Svuotare lo storico?") },
        text = { Text("Tutti gli eventi salvati sul telefono verranno eliminati.") },
    )
}
