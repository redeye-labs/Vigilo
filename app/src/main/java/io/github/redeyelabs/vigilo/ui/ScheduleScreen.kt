package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.data.ScheduleEntity
import io.github.redeyelabs.vigilo.work.Scheduler
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun daysText(mask: Int): String = when (mask) {
    0b1111111 -> "Tutti i giorni"; 0b0011111 -> "Lun–Ven"; 0b1100000 -> "Weekend"
    else -> DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.joinToString(" ") { Scheduler.dayLabel(it) }
}

@Composable
fun ScheduleScreen(vm: MainViewModel, onBack: (() -> Unit)? = null) {
    val rules by vm.schedules.collectAsState()
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.ITALY) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxSize()) {
            ScreenHeader(
                "Programma",
                "Quando accendere o spegnere la Sentinella. Esempio: ON alle 22:00 e OFF alle 07:00 nei giorni feriali. Android può ritardare di qualche minuto. Serve il proxy per comandi firmati.",
                onBack = onBack,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 88.dp)) {
                items(rules, key = { it.id }) { r ->
                    Card(
                        onClick = { editing = r },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "%02d:%02d · Sentinella %s".format(r.hour, r.minute, if (r.enableSentry) "ON" else "OFF"),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(daysText(r.daysMask), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Scheduler.nextOccurrence(r)?.takeIf { r.active }?.let {
                                    Text("Prossima: ${it.format(fmt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            VigiloSwitch(checked = r.active, onCheckedChange = { vm.saveSchedule(r.copy(active = it)) })
                            IconButton(onClick = { vm.deleteSchedule(r) }) { Icon(Icons.Filled.Delete, "Elimina") }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = ScheduleEntity(daysMask = 0b0011111, hour = 22, minute = 0, enableSentry = true) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Nuova regola") },
        )
        editing?.let { r -> EditRuleDialog(r, onDismiss = { editing = null }, onSave = { vm.saveSchedule(it); editing = null }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditRuleDialog(rule: ScheduleEntity, onDismiss: () -> Unit, onSave: (ScheduleEntity) -> Unit) {
    val time = rememberTimePickerState(rule.hour, rule.minute, is24Hour = true)
    var mask by remember { mutableIntStateOf(rule.daysMask) }
    var enable by remember { mutableStateOf(rule.enableSentry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "Nuova regola" else "Modifica regola") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = enable, onClick = { enable = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Accendi") }
                    SegmentedButton(selected = !enable, onClick = { enable = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Spegni") }
                }
                TimeInput(state = time)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        val bit = 1 shl (d.value - 1)
                        FilterChip(selected = mask and bit != 0, onClick = { mask = mask xor bit }, label = { Text(Scheduler.dayLabel(d)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = mask != 0, onClick = {
                onSave(rule.copy(daysMask = mask, hour = time.hour, minute = time.minute, enableSentry = enable))
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
