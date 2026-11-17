package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.data.ScheduleEntity
import io.github.redeyelabs.vigilo.work.Scheduler
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val WEEKDAYS = 0b0011111
private const val WEEKEND = 0b1100000
private const val ALL_DAYS = 0b1111111
private val DAY_LETTERS = listOf("L", "M", "M", "G", "V", "S", "D")
private val DAY_SHORT = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")

/** Giorni in breve: «Feriali», «Weekend», «Tutti i giorni» o l'elenco. */
fun scheduleDaysText(mask: Int): String = when (mask and ALL_DAYS) {
    ALL_DAYS -> "Tutti i giorni"
    WEEKDAYS -> "Feriali"
    WEEKEND -> "Weekend"
    0 -> "Nessun giorno"
    else -> (0..6).filter { mask and (1 shl it) != 0 }.joinToString(" ") { DAY_SHORT[it] }
}

private fun ScheduleEntity.toRule() = Backend.Rule(id, hour, minute, daysMask, enableSentry, active)
private fun Backend.Rule.toEntity() = ScheduleEntity(id = id, hour = hour, minute = minute, daysMask = daysMask, enableSentry = enable, active = active)

@Composable
fun ScheduleScreen(vm: MainViewModel, onBack: (() -> Unit)? = null) {
    val where by vm.scheduleWhere.collectAsState()
    val server by vm.serverRules.collectAsState()
    val local by vm.schedules.collectAsState()
    LaunchedEffect(Unit) { vm.loadSchedules() }
    val rules = if (where == MainViewModel.ScheduleWhere.SERVER) server else local.map { it.toRule() }
    ScheduleScreenContent(
        rules = rules,
        where = where,
        onSave = vm::saveRule,
        onDelete = vm::deleteRule,
        onBack = onBack,
    )
}

@Composable
fun ScheduleScreenContent(
    rules: List<Backend.Rule>,
    where: MainViewModel.ScheduleWhere,
    onSave: (Backend.Rule) -> Unit,
    onDelete: (Backend.Rule) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf<Backend.Rule?>(null) }
    val newRule = { editing = Backend.Rule() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxSize()) {
            ScreenHeader(
                "Programma Sentinella",
                when (where) {
                    MainViewModel.ScheduleWhere.SERVER -> "Il server Vigilo accende e spegne la Sentinella agli orari scelti, anche a telefono spento."
                    MainViewModel.ScheduleWhere.PHONE -> "Le regole partono da questo telefono e possono tardare qualche minuto. Aggiorna il server Vigilo per farle eseguire a lui."
                    MainViewModel.ScheduleWhere.LOADING -> "Accendi e spegni la Sentinella in automatico."
                },
                onBack = onBack,
            )
            if (rules.isEmpty() && where != MainViewModel.ScheduleWhere.LOADING) {
                EmptySchedule(onAdd = newRule)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(rules, key = { it.id }) { r ->
                        RuleCard(
                            rule = r,
                            onEdit = { editing = r },
                            onToggle = { onSave(r.copy(active = it)) },
                            onDelete = { onDelete(r) },
                        )
                    }
                }
            }
        }
        if (rules.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = newRule,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Nuova regola") },
            )
        }
        editing?.let { r ->
            RuleDialog(r, onDismiss = { editing = null }, onSave = { onSave(it); editing = null })
        }
    }
}

@Composable
private fun EmptySchedule(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Schedule, null, tint = VigiloIconGray, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Nessuna regola", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Ad esempio: accendi alle 22:00 e spegni alle 7:00 nei giorni feriali.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Aggiungi regola")
        }
    }
}

@Composable
private fun RuleCard(rule: Backend.Rule, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val dim = if (rule.active) 1f else 0.45f
    val fmt = remember { DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ITALY) }
    VigiloCard(onClick = onEdit, contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 8.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%02d:%02d".format(rule.hour, rule.minute),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                    )
                    Spacer(Modifier.width(12.dp))
                    ActionChip(rule.enable, dim)
                }
                Spacer(Modifier.height(8.dp))
                DayDots(rule.daysMask, dim)
                Spacer(Modifier.height(6.dp))
                val next = Scheduler.nextOccurrence(rule.toEntity())?.takeIf { rule.active }
                Text(
                    scheduleDaysText(rule.daysMask) + (next?.let { " · prossima ${it.format(fmt)}" } ?: if (rule.active) "" else " · in pausa"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VigiloSwitch(checked = rule.active, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altre azioni", tint = VigiloIconGray) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Modifica") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Elimina", color = VigiloDanger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ActionChip(enable: Boolean, alpha: Float) {
    val bg = if (enable) VigiloDanger.copy(alpha = 0.18f * alpha) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    val fg = if (enable) VigiloDanger.copy(alpha = alpha) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    Surface(shape = CircleShape, color = bg) {
        Text(
            if (enable) "Accendi" else "Spegni",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun DayDots(mask: Int, alpha: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DAY_LETTERS.forEachIndexed { i, l ->
            val on = mask and (1 shl i) != 0
            Box(
                Modifier.size(24.dp).background(
                    if (on) MaterialTheme.colorScheme.onSurface.copy(alpha = alpha) else Color.Transparent,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f * alpha),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDialog(rule: Backend.Rule, onDismiss: () -> Unit, onSave: (Backend.Rule) -> Unit) {
    val time = rememberTimePickerState(rule.hour, rule.minute, is24Hour = true)
    var mask by remember { mutableIntStateOf(rule.daysMask) }
    var enable by remember { mutableStateOf(rule.enable) }
    val segColors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.onSurface,
        activeContentColor = MaterialTheme.colorScheme.surface,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (rule.id == 0L) "Nuova regola" else "Modifica regola") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = enable, onClick = { enable = true }, shape = SegmentedButtonDefaults.itemShape(0, 2), colors = segColors) {
                        Text("Accendi")
                    }
                    SegmentedButton(selected = !enable, onClick = { enable = false }, shape = SegmentedButtonDefaults.itemShape(1, 2), colors = segColors) {
                        Text("Spegni")
                    }
                }
                TimePicker(
                    state = time,
                    colors = TimePickerDefaults.colors(
                        selectorColor = MaterialTheme.colorScheme.onSurface,
                        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.surface,
                        clockDialSelectedContentColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).forEach { i ->
                        val bit = 1 shl i
                        val on = mask and bit != 0
                        Box(
                            Modifier.size(34.dp)
                                .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .clickable { mask = mask xor bit },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                DAY_LETTERS[i],
                                style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Feriali" to WEEKDAYS, "Weekend" to WEEKEND, "Tutti i giorni" to ALL_DAYS).forEach { (label, m) ->
                        TextButton(onClick = { mask = m }) {
                            Text(
                                label,
                                color = if (mask == m) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (mask == m) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = mask != 0, onClick = {
                onSave(rule.copy(daysMask = mask, hour = time.hour, minute = time.minute, enable = enable))
            }) { Text("Salva", color = if (mask != 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}
