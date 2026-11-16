package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.material3.TextButton
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
import io.github.redeyelabs.vigilo.data.EventEntity
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(vm: MainViewModel, onOpen: (Screen) -> Unit) {
    val s by vm.snapshot.collectAsState()
    val loggedIn by vm.loggedIn.collectAsState()
    val pending by vm.pendingAction.collectAsState()
    val pull by vm.pullRefreshing.collectAsState()
    val events by vm.events.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    LaunchedEffect(loggedIn) {
        if (loggedIn && vm.prefs.vin.isNotBlank() && s == null) vm.refresh()
    }

    val name = s?.name ?: vm.prefs.vehicleName.ifBlank { "Vigilo" }
    val ready = loggedIn && vm.prefs.vin.isNotBlank()
    val last24 = remember(events, now) { eventsInLast24h(events, now) }
    val alerts24 = sentryAlertCount(last24)
    val buckets = remember(last24, now) { hourlyEventBuckets(last24, now) }
    StatusScreenContent(
        name = name,
        snapshot = s,
        ready = ready,
        now = now,
        showHero = vm.prefs.showHeroCar,
        pendingSentry = pending == "sentry",
        pullRefreshing = pull,
        last24 = last24,
        alerts24 = alerts24,
        buckets = buckets,
        notificationsSubtitle = notificationsSubtitle(
            vm.prefs.alertUnlocked,
            vm.prefs.alertWindows,
            vm.prefs.alertChargeComplete,
            vm.prefs.alertLowBattery,
        ),
        actionsSubtitle = actionsSubtitle(vm.prefs.autoFlash, vm.prefs.autoHonk),
        onRefresh = { if (ready) vm.refresh() },
        onPullRefresh = { vm.pullRefresh() },
        onToggleSentry = { vm.setSentry(s?.sentry != true) },
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
    last24: List<EventEntity>,
    alerts24: Int,
    buckets: IntArray,
    notificationsSubtitle: String,
    actionsSubtitle: String?,
    onRefresh: () -> Unit,
    onPullRefresh: () -> Unit,
    onToggleSentry: () -> Unit,
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
                    "Per iniziare, apri Impostazioni e collega il tuo account Tesla.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            Events24hCard(
                total = last24.size,
                alerts = alerts24,
                buckets = buckets,
                recent = last24.take(3),
                onSeeAll = { onOpen(Screen.HISTORY) },
            )

            Spacer(Modifier.height(8.dp))
            MenuRow(
                Icons.Filled.Notifications,
                "Notifiche",
                subtitle = notificationsSubtitle,
                onClick = { onOpen(Screen.NOTIFICATIONS) },
            )
            MenuRow(
                Icons.Filled.Schedule,
                "Programmazione Sentinella",
                onClick = { onOpen(Screen.SCHEDULE) },
            )
            MenuRow(
                Icons.Filled.FlashOn,
                "Azioni automatiche",
                subtitle = actionsSubtitle,
                onClick = { onOpen(Screen.ACTIONS) },
            )
            MenuRow(
                Icons.Filled.Tune,
                "Comandi",
                subtitle = commandSubtitle(snapshot),
                onClick = { onOpen(Screen.COMMANDS) },
            )
            MenuRow(
                Icons.Filled.History,
                "Storico eventi",
                onClick = { onOpen(Screen.HISTORY) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
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
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).clickable(onClick = onRefresh)) {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (s?.locked == false) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = when (s?.locked) { true -> "Chiusa"; false -> "Aperta"; null -> "Chiusura" },
                    tint = VigiloIconGray,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                MiniBattery(s)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (!ready) "Collega l'account in Impostazioni" else formatUpdatedAgo(s?.timestamp, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Menu, contentDescription = "Impostazioni", tint = VigiloIconGray)
        }
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
private fun Events24hCard(
    total: Int,
    alerts: Int,
    buckets: IntArray,
    recent: List<EventEntity>,
    onSeeAll: () -> Unit,
) {
    VigiloCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ultime 24 ore", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        total == 0 -> "Nessun evento registrato"
                        alerts == 0 -> if (total == 1) "1 evento" else "$total eventi"
                        else -> "$total eventi, di cui $alerts avviso${if (alerts == 1) "" else "i"} Sentinella"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSeeAll) { Text("Vedi tutto") }
        }
        Spacer(Modifier.height(12.dp))
        HourlySparkline(buckets)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            recent.forEach { e ->
                Text(
                    e.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (io.github.redeyelabs.vigilo.data.EventType.isAlert(e.type))
                        VigiloDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HourlySparkline(buckets: IntArray) {
    val max = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val empty = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val n = buckets.size
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        buckets.forEachIndexed { i, v ->
            val h = if (v <= 0) 3.dp.toPx() else (size.height * (v.toFloat() / max)).coerceAtLeast(3.dp.toPx())
            val x = i * (w + gap)
            drawRoundRect(
                color = if (v > 0) bar else empty,
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun MiniBattery(s: Snapshot?) {
    val pct = s?.batteryLevel
    val charging = s?.chargingState == "Charging"
    val fill = when {
        charging -> VigiloOk
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(26.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(((pct ?: 0).coerceIn(0, 100)) / 100f)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (pct != null) fill else MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Box(
            Modifier
                .padding(start = 1.dp)
                .width(2.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (pct != null) "$pct%" else "—",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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

fun notificationsSubtitle(unlocked: Boolean, windows: Boolean, charge: Boolean, low: Boolean): String {
    val n = listOf(unlocked, windows, charge, low).count { it }
    return if (n == 0) "Tutti gli avvisi sono spenti" else "$n avvisi attivi"
}

fun actionsSubtitle(autoFlash: Boolean, autoHonk: Boolean): String? {
    val parts = buildList {
        if (autoFlash) add("fari")
        if (autoHonk) add("clacson")
    }
    return if (parts.isEmpty()) "Nessuna azione automatica" else "Reazione: ${parts.joinToString(" e ")}"
}

fun chargingLabel(s: String?) = when (s) {
    "Charging" -> "in corso"; "Complete" -> "completata"; "Disconnected" -> "cavo scollegato"
    "Stopped" -> "interrotta"; "Starting" -> "in avvio"; "NoPower" -> "nessuna corrente"; null -> "—"; else -> s
}
