package io.github.redeyelabs.vigilo.ui

import androidx.compose.ui.platform.LocalContext
import io.github.redeyelabs.vigilo.work.BatteryOpt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Snapshot
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(vm: MainViewModel, onOpen: (Screen) -> Unit) {
    val s by vm.snapshot.collectAsState()
    val loggedIn by vm.loggedIn.collectAsState()
    val pending by vm.pendingAction.collectAsState()
    val pull by vm.pullRefreshing.collectAsState()
    val events by vm.events.collectAsState()
    val actions by vm.actions.collectAsState()
    val monitoringEnabled by vm.monitoringEnabled.collectAsState()
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    LaunchedEffect(loggedIn) {
        if (loggedIn && vm.prefs.vin.isNotBlank() && s == null) vm.refresh()
        if (loggedIn) vm.loadActions()
    }

    val name = vm.prefs.displayVehicleName(s?.name).ifBlank { "Vigilo" }
    val ready = loggedIn && vm.prefs.vin.isNotBlank()
    val sessionStartedAt = vm.prefs.monitoringStartedAt
    val sessionEndedAt = if (monitoringEnabled) now else vm.prefs.monitoringStoppedAt
    val sessionEvents = remember(events, sessionStartedAt, sessionEndedAt) {
        if (sessionStartedAt <= 0L || sessionEndedAt < sessionStartedAt) emptyList()
        else events.filter { it.timestamp in sessionStartedAt..sessionEndedAt }
    }
    val sessionAlerts = sentryAlertCount(sessionEvents)
    StatusScreenContent(
        name = name,
        snapshot = s,
        ready = ready,
        now = now,
        showHero = vm.prefs.showHeroCar,
        pendingSentry = pending == "sentry",
        pullRefreshing = pull,
        sessionEvents = sessionEvents.size,
        sessionAlerts = sessionAlerts,
        sessionStartedAt = sessionStartedAt,
        monitoringEnabled = monitoringEnabled,
        notificationsSubtitle = NOTIFICATIONS_SUBTITLE,
        actionsSubtitle = actionsSubtitle(actions),
        onRefresh = { if (ready) vm.refresh() },
        onPullRefresh = { vm.pullRefresh() },
        onToggleSentry = { vm.setSentry(s?.sentry != true) },
        onToggleMonitoring = { enabled ->
            vm.setAppMonitoringEnabled(enabled)
            if (enabled && !BatteryOpt.isExempt(ctx)) BatteryOpt.request(ctx)
        },
        onOpen = onOpen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreenContent(
    name: String,
    snapshot: Snapshot?,
    ready: Boolean,
    now: Long,
    showHero: Boolean,
    pendingSentry: Boolean,
    pullRefreshing: Boolean,
    sessionEvents: Int,
    sessionAlerts: Int,
    sessionStartedAt: Long,
    monitoringEnabled: Boolean,
    notificationsSubtitle: String,
    actionsSubtitle: String = ACTIONS_SUBTITLE,
    onRefresh: () -> Unit,
    onPullRefresh: () -> Unit,
    onToggleSentry: () -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
    onOpen: (Screen) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = onPullRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            CompactHeader(
                name = name,
                s = snapshot,
                ready = ready,
                now = now,
                onRefresh = { if (ready) onRefresh() },
                onSettings = { onOpen(Screen.SETTINGS) },
            )

            if (showHero) {
                Spacer(Modifier.height(8.dp))
                HeroCar(Modifier.height(120.dp))
            }

            if (!ready) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Per iniziare, apri la Guida e collega l'app al tuo server Vigilo (Home Assistant o Docker).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.Button(onClick = { onOpen(Screen.GUIDE) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Apri la guida")
                }
            }

            Spacer(Modifier.height(16.dp))
            SentryStatusCard(
                active = snapshot?.sentry == true,
                ready = ready,
                busy = pendingSentry,
                subtitle = sentrySubtitle(snapshot, ready),
                onToggle = onToggleSentry,
            )

            Spacer(Modifier.height(16.dp))
            MonitoringSummaryCard(
                total = sessionEvents,
                alerts = sessionAlerts,
                startedAt = sessionStartedAt,
                active = monitoringEnabled,
                enabled = ready,
                onToggle = onToggleMonitoring,
            )

            HomeModuleGroup("Controllo") {
                MenuRow(Icons.Filled.Tune, "Comandi", subtitle = commandSubtitle(snapshot), onClick = { onOpen(Screen.COMMANDS) })
                MenuRow(Icons.Filled.LocationOn, "Posizione attuale", onClick = { onOpen(Screen.MAP) })
                MenuRow(Icons.Filled.MonitorHeart, "Salute del veicolo", subtitle = "Pneumatici e telemetria", onClick = { onOpen(Screen.V2_DIAGNOSTICS) })
            }
            HomeModuleGroup("Energia e percorrenze") {
                MenuRow(Icons.Filled.BatteryChargingFull, "Batteria", subtitle = "Autonomia e capacità BMS", onClick = { onOpen(Screen.V2_BATTERY) })
                MenuRow(Icons.Filled.ElectricalServices, "Ricariche", subtitle = "Sessioni, energia e costi", onClick = { onOpen(Screen.V2_CHARGING) })
                MenuRow(Icons.Filled.Route, "Viaggi", subtitle = "Distanza e consumi in marcia", onClick = { onOpen(Screen.V2_TRIPS) })
                MenuRow(Icons.Filled.LocalParking, "Parcheggi", subtitle = "Consumi osservati da fermo", onClick = { onOpen(Screen.V2_PARKING) })
            }
            HomeModuleGroup("Sicurezza e automazioni") {
                MenuRow(Icons.Filled.Notifications, "Notifiche", subtitle = notificationsSubtitle, onClick = { onOpen(Screen.NOTIFICATIONS) })
                MenuRow(Icons.Filled.Schedule, "Automazioni", subtitle = "Condizioni e scenari", onClick = { onOpen(Screen.V2_AUTOMATIONS) })
                MenuRow(Icons.Filled.Schedule, "Programma Sentinella", subtitle = "Orari di attivazione e spegnimento", onClick = { onOpen(Screen.SCHEDULE) })
                MenuRow(Icons.Filled.FlashOn, "Azioni automatiche", subtitle = actionsSubtitle, onClick = { onOpen(Screen.ACTIONS) })
                MenuRow(Icons.Filled.Security, "Sicurezza", subtitle = "Statistiche Sentinella", onClick = { onOpen(Screen.V2_SECURITY) })
            }
            HomeModuleGroup("Dati e impostazioni") {
                MenuRow(Icons.Filled.History, "Storico eventi", onClick = { onOpen(Screen.HISTORY) })
                MenuRow(Icons.Filled.History, "Backup e report", subtitle = "Esportazione dati e storico", onClick = { onOpen(Screen.V2_BACKUP) })
                MenuRow(Icons.Filled.Settings, "Impostazioni", onClick = { onOpen(Screen.SETTINGS) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeModuleGroup(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    VigiloCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), content = content)
}

@Composable
private fun CompactHeader(
    name: String,
    s: Snapshot?,
    ready: Boolean,
    now: Long,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Vigilo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.headlineLarge)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Impostazioni", tint = VigiloIconGray)
        }
    }
    VigiloCard(onClick = if (ready) onRefresh else null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Carica", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s?.batteryLevel?.let { "$it%" } ?: "—", style = MaterialTheme.typography.displayMedium)
            }
            Column(Modifier.weight(1f)) {
                Text("Autonomia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s?.rangeKm?.let { "%.0f km".format(Locale.ITALY, it) } ?: "—", style = MaterialTheme.typography.headlineSmall)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val lock = when (s?.locked) { true -> "Chiusa"; false -> "Aperta"; null -> "Chiusura sconosciuta" }
        Text(lock, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ready) formatUpdatedAgo(s?.timestamp, now) else "Server da collegare",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SentryStatusCard(
    active: Boolean,
    ready: Boolean,
    busy: Boolean,
    subtitle: String,
    onToggle: () -> Unit,
) {
    VigiloCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SentryIcon(active = active, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Sentinella", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (active) "Attiva" else "Spenta",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (active) VigiloDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = VigiloIconGray)
            } else {
                VigiloSwitch(checked = active, onCheckedChange = { onToggle() }, enabled = ready, danger = true)
            }
        }
    }
}

@Composable
private fun MonitoringSummaryCard(
    total: Int,
    alerts: Int,
    startedAt: Long,
    active: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val since = remember(startedAt) {
        if (startedAt > 0L) SimpleDateFormat("dd/MM 'alle' HH:mm", Locale.ITALY).format(Date(startedAt)) else null
    }
    VigiloCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = VigiloIconGray, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Monitoraggio avvisi", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        active -> "Attivo dal ${since ?: "momento attuale"}"
                        since != null -> "Ultima sessione: dal $since"
                        else -> "Non ancora attivato"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VigiloSwitch(checked = active, onCheckedChange = onToggle, enabled = enabled)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$total", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Eventi registrati", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text("$alerts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Avvisi Sentinella", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun sentrySubtitle(s: Snapshot?, ready: Boolean): String {
    if (!ready) return "Collega l'account per usarla"
    if (s == null) return "Trascina in basso per aggiornare"
    return when {
        s.chargingState == "Charging" -> "In carica"
        !s.online -> when (s.connectionState) {
            "asleep" -> "Auto addormentata"
            "offline" -> "Auto offline"
            else -> "Non connessa"
        }
        s.parked -> "Auto parcheggiata"
        else -> "Auto online"
    }
}

private fun commandSubtitle(s: Snapshot?): String? = when (s?.locked) {
    true -> "Chiusa"
    false -> "Aperta"
    null -> null
}

/** Gli avvisi Sentinella li gestisce il server Vigilo. */
const val NOTIFICATIONS_SUBTITLE = "Avvisi Sentinella dal server Vigilo"

fun chargingLabel(s: String?) = when (s) {
    "Charging" -> "in corso"; "Complete" -> "completata"; "Disconnected" -> "cavo scollegato"
    "Stopped" -> "interrotta"; "Starting" -> "in avvio"; "NoPower" -> "nessuna corrente"; null -> "—"; else -> s
}
