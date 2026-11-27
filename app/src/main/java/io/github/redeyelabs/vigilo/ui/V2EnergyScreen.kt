package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import java.time.Instant
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
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

/** Analysis from Tesla readings only, hidden behind opt-in modules. */
@Composable
fun V2EnergyScreen(vm: MainViewModel, onBack: () -> Unit, onlyModule: String? = null) {
    val paired by vm.backendMode.collectAsState()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(EnergyScreenData()) }
    var tariffInput by remember { mutableStateOf("") }
    var tariffSaved by remember { mutableStateOf(false) }

    LaunchedEffect(paired, refresh, onlyModule) {
        if (!paired) return@LaunchedEffect
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val backend = Backend(vm.prefs)
                val capabilities = backend.v2Capabilities().modules
                val batteryOn = (onlyModule == null || onlyModule == "battery") && capabilities.any { it.id == "battery" && it.enabled }
                val chargingOn = (onlyModule == null || onlyModule == "charging") && capabilities.any { it.id == "charging" && it.enabled }
                val tripsOn = (onlyModule == null || onlyModule == "trips") && capabilities.any { it.id == "trips" && it.enabled }
                EnergyScreenData(
                    modules = capabilities,
                    battery = if (batteryOn) backend.v2BatterySummary() else null,
                    trend = if (batteryOn) backend.v2CapacityTrend() else null,
                    charges = if (chargingOn) backend.v2ChargingSessions() else emptyList(),
                    trips = if (tripsOn) backend.v2TripObservations() else emptyList(),
                    tariff = if (chargingOn) backend.v2ChargingTariff() else null,
                )
            }
            data = result
            tariffInput = result.tariff?.toString()?.replace('.', ',').orEmpty()
            tariffSaved = result.tariff != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Impossibile leggere le statistiche."
        } finally {
            loading = false
        }
    }

    var days by remember { mutableIntStateOf(30) }
    var energyReport by remember { mutableStateOf<Backend.V2ObservedEnergy?>(null) }
    var energyError by remember { mutableStateOf<String?>(null) }
    val tripsEnabled = data.modules.any { it.id == "trips" && it.enabled }
    LaunchedEffect(paired, tripsEnabled, days, refresh, onlyModule) {
        energyReport = null
        energyError = null
        if (paired && tripsEnabled && onlyModule != "charging") {
            try {
                energyReport = withContext(Dispatchers.IO) { Backend(vm.prefs).v2ObservedEnergy(if (days == 0) 90 else days) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { energyError = e.message ?: "Impossibile leggere i consumi osservati." }
        }
    }
    EnergyScreenContent(
        data = data, energyReport = energyReport, onlyModule = onlyModule,
        paired = paired, days = days, loading = loading, working = working,
        error = error, energyError = energyError, tariffInput = tariffInput, tariffSaved = tariffSaved,
        onPeriodChange = { days = it },
        onEditTariff = { tariffInput = it.take(12); tariffSaved = false },
        onSetModule = { id, active ->
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Backend(vm.prefs).v2SetModule(id, active) }
                    refresh++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Impossibile modificare il modulo."
                } finally {
                    working = false
                }
            }
        },
        onSaveTariff = { tariff ->
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Backend(vm.prefs).v2SetChargingTariff(tariff) }
                    tariffSaved = true
                    refresh++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Tariffa non salvata."
                } finally {
                    working = false
                }
            }
        },
        onRefresh = { refresh++ },
        onBack = onBack,
    )
}

@Composable
internal fun EnergyScreenContent(
    data: EnergyScreenData,
    energyReport: Backend.V2ObservedEnergy?,
    onlyModule: String?,
    paired: Boolean,
    days: Int,
    loading: Boolean,
    working: Boolean,
    error: String?,
    energyError: String?,
    tariffInput: String,
    tariffSaved: Boolean,
    onPeriodChange: (Int) -> Unit,
    onEditTariff: (String) -> Unit,
    onSetModule: (String, Boolean) -> Unit,
    onSaveTariff: (Double?) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    now: Instant = Instant.now(),
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    var chargingDays by remember { mutableIntStateOf(30) }
    val (modules, battery, capacityTrend, charges, trips) = data
    val cutoff = now.minus(days.toLong(), ChronoUnit.DAYS)
    val chargingCutoff = now.minus(chargingDays.toLong(), ChronoUnit.DAYS)
    val visibleCharges = charges.filter { inDashboardPeriod(it.lastSeen, chargingDays, chargingCutoff) }
    val visibleTrips = trips.filter { inDashboardPeriod(it.lastSeen, days, cutoff) }

    fun enabled(id: String) = modules.any { it.id == id && it.enabled }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(when (onlyModule) { "battery" -> "Batteria"; "charging" -> "Ricariche"; "trips" -> "Viaggi"; "parking" -> "Parcheggi"; else -> "Batteria e analisi" }, "Valori Tesla reali; stime sempre dichiarate.", onBack = onBack)
        if (!paired) {
            VigiloCard { Text("Collega prima il server Vigilo.") }
        } else {
            if (loading) Text("Aggiornamento dati in corso…")
            if (error != null) VigiloCard { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            listOf(
                Triple("battery", "Salute batteria", "Capacità BMS, energia residua, autonomia"),
                Triple("charging", "Ricariche e costi", "Energia immessa nella batteria e tariffa personale"),
                Triple("trips", "Viaggi e consumi", "Distanze osservate dal contachilometri"),
                Triple("parking", "Consumi da fermo", "Soste osservate con e senza Sentinella"),
            ).filter { onlyModule == null || it.first == onlyModule }.forEach { (id, title, description) ->
                val moduleId = if (id == "parking") "trips" else id
                val active = enabled(moduleId)
                if (active) {
                    when (id) {
                        "charging" -> PeriodPicker(chargingDays, allowAll = false, charging = true) { chargingDays = it }
                        "parking" -> PeriodPicker(days, allowAll = false, onChange = onPeriodChange)
                        "trips" -> PeriodPicker(days, onChange = onPeriodChange)
                    }
                }
                VigiloCard {
                    val accent = MaterialTheme.colorScheme.onSurface
                    Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
                    if (!active) Text(description, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (active && id == "battery") {
                        battery?.let { b ->
                            Spacer(Modifier.height(16.dp))
                            BatteryGauge(b.chargePercent, accent)
                            MetricPair("Autonomia nominale", b.ratedRangeKm?.let { fmt(it, "km") } ?: "—", "Energia residua", b.remainingKWh?.let { fmt(it, "kWh") } ?: "—", accent)
                            BatteryHealthPanel(b.healthPercent, b.fullPackKWh, b.stale, accent)
                            val efficiency = energyReport?.whPerKm?.takeIf { it > 0 }
                            MetricPair("Autonomia dai consumi (stima)", if (efficiency != null && b.remainingKWh != null) fmt(b.remainingKWh * 1000 / efficiency, "km") else "—", "Consumo osservato", efficiency?.let { fmt(it, "Wh/km") } ?: "—", accent)
                            MetricPair("Autonomia nominale al 100% (stima)", b.ratedFullRangeKm?.let { fmt(it, "km") } ?: "—", "Cicli di carica equivalenti (stima)", b.equivalentChargeCycles?.let { fmt(it, "") } ?: "—", accent)
                            MetricPair("Temperatura minima batteria", b.tempMinC?.let { fmt(it, "°C") } ?: "—", "Temperatura massima batteria", b.tempMaxC?.let { fmt(it, "°C") } ?: "—", accent)
                            Text("Cicli stimati: energia totale caricata divisa per capacità BMS attuale; non sono cicli di fabbrica certificati.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (b.stale) {
                                Text("Dati precedenti: la Tesla non ha trasmesso letture recenti.",
                                    color = MaterialTheme.colorScheme.error)
                            }
                            if (b.latestReceivedAt.isNotBlank()) {
                                Text("Ultima lettura: ${b.latestReceivedAt}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(b.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } ?: Text("Nessuna misura disponibile.")
                        capacityTrend?.let { observation ->
                            val history = observation.history.takeLast(12)
                            if (history.isNotEmpty()) {
                                Text("Capacità BMS · mediane settimanali", style = MaterialTheme.typography.titleMedium)
                                EnergyBars(history.map { it.capacityKWh }, accent, "kWh",
                                    history.map { shortDate(it.week).substringBefore(' ') })
                            }
                            if (observation.available && observation.trend != null) {
                                val trend = observation.trend
                                Text("Variazione capacità BMS nel periodo: " + fmt(trend.percentChange, "%"))
                                Text("Capacità iniziale rilevata: " + fmt(trend.firstKWh, "kWh"))
                                Text("Capacità recente: " + fmt(trend.latestKWh, "kWh"))
                                Text(trend.note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(observation.note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (active && id == "charging") {
                        MetricPair("Energia osservata", if (visibleCharges.isEmpty()) "—" else fmt(visibleCharges.sumOf { it.batteryKWh }, "kWh"), "Costo stimato", if (visibleCharges.isNotEmpty() && visibleCharges.all { it.costEUR != null }) fmt(visibleCharges.sumOf { it.costEUR ?: 0.0 }, "€") else "—", accent)
                        MetricPair("Sessioni osservate", visibleCharges.size.toString(), "Energia media", if (visibleCharges.isEmpty()) "—" else fmt(visibleCharges.map { it.batteryKWh }.average(), "kWh"), accent)
                        Text("Cronologia ricariche", style = MaterialTheme.typography.titleMedium)
                        EnergyBars(visibleCharges.takeLast(12).map { it.batteryKWh }, accent, "kWh", visibleCharges.takeLast(12).map { shortDate(it.lastSeen) })
                        visibleCharges.takeLast(8).asReversed().forEach { session ->
                            MetricPair(shortDate(session.lastSeen), fmt(session.batteryKWh, "kWh"), "Costo stimato", session.costEUR?.let { fmt(it, "€") } ?: "—", accent)
                            val power = session.averageObservedPowerKW
                            val observed = session.observedChargingMinutes
                            if (power != null && observed != null) {
                                Text("Potenza stimata in batteria: ${fmt(power, "kW")} su ${observed.toInt()} min osservati",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            observedMinutes(session.firstSeen, session.lastSeen)?.let { minutes ->
                                Text("Finestra osservata: $minutes min", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (visibleCharges.isEmpty()) Text("Nessuna ricarica osservata nel periodo.")
                        Text(
                            "La telemetria può essere incompleta. I kWh nella batteria non coincidono necessariamente con quelli fatturati.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = tariffInput,
                            onValueChange = onEditTariff,
                            label = { Text("Tariffa personale (€/kWh)") },
                            placeholder = { Text("Facoltativa") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            enabled = !working && (tariffInput.isBlank() ||
                                tariffInput.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10.0 } == true),
                            onClick = { onSaveTariff(tariffInput.replace(',', '.').toDoubleOrNull()) },
                        ) { Text(if (tariffSaved) "Tariffa salvata" else "Salva tariffa") }
                    }
                    if (active && id == "parking") {
                        val report = energyReport
                        val average = report?.parkedHours?.takeIf { it > 0 }?.let { hours ->
                            report.parkedKWh?.div(hours)
                        }
                        MetricPair("Energia da fermo", report?.parkedKWh?.let { fmt(it, "kWh") } ?: "—",
                            "Consumo orario", average?.let { fmt(it, "kWh/h") } ?: "—", accent)
                        MetricPair("Sentinella attiva", report?.sentryKWh?.let { fmt(it, "kWh") } ?: "—",
                            "Sentinella spenta", report?.sentryOffKWh?.let { fmt(it, "kWh") } ?: "—", accent)
                        val parkedCost = estimatedEnergyCost(report?.parkedKWh, report?.tariffEURPerKWh)
                        MetricPair("Tempo osservato", report?.parkedHours?.let { fmt(it, "h") } ?: "—",
                            "Costo energetico stimato", parkedCost, accent)
                        Text(energyError ?: report?.note ?: "Dati energetici non ancora disponibili.",
                            style = MaterialTheme.typography.bodySmall)
                        val recent = report?.parking.orEmpty().takeLast(12)
                        if (recent.isNotEmpty()) {
                            Text("Ultimi intervalli di sosta", style = MaterialTheme.typography.titleMedium)
                            EnergyBars(recent.map { it.energyKWh }, accent, "kWh", recent.map { shortDate(it.lastSeen) })
                            recent.asReversed().forEach { interval ->
                                val sentry = when (interval.sentry) {
                                    "on" -> "Sentinella attiva"
                                    "off" -> "Sentinella spenta"
                                    else -> "Stato Sentinella incerto"
                                }
                                val minutes = observedMinutes(interval.firstSeen, interval.lastSeen)
                                MetricPair(shortDate(interval.lastSeen), fmt(interval.energyKWh, "kWh"),
                                    sentry, minutes?.let { "$it min" } ?: "—", accent)
                            }
                        }
                    }
                    if (active && id == "trips") {
                        MetricPair("Distanza osservata", if (visibleTrips.isEmpty()) "—" else fmt(visibleTrips.sumOf { it.distanceKm }, "km"), "Tratti osservati", visibleTrips.size.toString(), accent)
                        MetricPair("Distanza media per tratto", if (visibleTrips.isEmpty()) "—" else fmt(visibleTrips.map { it.distanceKm }.average(), "km"), "Tratto più lungo", visibleTrips.maxOfOrNull { it.distanceKm }?.let { fmt(it, "km") } ?: "—", accent)
                        Text("Energia in marcia", style = MaterialTheme.typography.titleMedium)
                        MetricPair("Consumo in movimento", energyReport?.whPerKm?.let { fmt(it, "Wh/km") } ?: "—", "Energia in movimento", energyReport?.drivingKWh?.let { fmt(it, "kWh") } ?: "—", accent)
                        MetricPair("Consumo osservato", energyReport?.whPerKm?.let { fmt(it / 10, "kWh/100 km") } ?: "—",
                            "Distanza con misura energetica", energyReport?.drivingKm?.let { fmt(it, "km") } ?: "—", accent)
                        Text(energyError ?: energyReport?.note ?: "Dati energetici non ancora disponibili.", style = MaterialTheme.typography.bodySmall)
                        val drives = energyReport?.driving.orEmpty().takeLast(12).filter { it.distanceKm > 0 }
                        if (drives.isNotEmpty()) {
                            Text("Tratti con energia e distanza abbinate", style = MaterialTheme.typography.titleMedium)
                            EnergyBars(drives.map { it.energyKWh * 100 / it.distanceKm }, accent,
                                "kWh/100 km", drives.map { shortDate(it.lastSeen) })
                            drives.asReversed().forEach { drive ->
                                val minutes = observedMinutes(drive.firstSeen, drive.lastSeen)
                                MetricPair(shortDate(drive.lastSeen), fmt(drive.distanceKm, "km"),
                                    "Durata osservata", minutes?.let { "$it min" } ?: "—", accent)
                                MetricPair("Energia osservata", fmt(drive.energyKWh, "kWh"), "Costo energetico stimato",
                                    estimatedEnergyCost(drive.energyKWh, energyReport?.tariffEURPerKWh), accent)
                            }
                        }
                        Text("Cronologia da contachilometri", style = MaterialTheme.typography.titleMedium)
                        EnergyBars(visibleTrips.takeLast(12).map { it.distanceKm }, accent, "km", visibleTrips.takeLast(12).map { shortDate(it.lastSeen) })
                        visibleTrips.takeLast(8).asReversed().forEach { trip ->
                            MetricPair(shortDate(trip.lastSeen), fmt(trip.distanceKm, "km"), "Fonte", "Odometro", accent)
                        }
                        if (visibleTrips.isEmpty()) Text("Nessun tratto osservato nel periodo.")
                        Text(
                            "Percorrenze da odometro. I consumi si riferiscono solo agli intervalli osservati e classificabili.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    enabled = !loading && !working,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSetModule(moduleId, !active) },
                ) { Text(if (active) "Disattiva raccolta" else "Attiva raccolta") }
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = !working) {
                Text("Aggiorna statistiche")
            }
        }
    }
}

internal data class EnergyScreenData(
    val modules: List<Backend.V2Module> = emptyList(),
    val battery: Backend.V2BatteryObservations? = null,
    val trend: Backend.V2CapacityTrendResponse? = null,
    val charges: List<Backend.V2ChargingSession> = emptyList(),
    val trips: List<Backend.V2TripObservation> = emptyList(),
    val tariff: Double? = null,
)

private fun fmt(n: Double, unit: String): String =
    String.format(Locale.ITALY, "%.1f %s", n, unit)

@Composable
private fun EnergyBars(values: List<Double>, accent: Color, unit: String, labels: List<String>) {
    val clean = values.filter { it.isFinite() && it >= 0.0 }
    val peak = clean.maxOrNull() ?: return
    if (peak <= 0.0) return
    Text("Ultime osservazioni · massimo " + fmt(peak, unit), style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(112.dp).padding(vertical = 8.dp)) {
        val slot = size.width / clean.size
        clean.forEachIndexed { index, value ->
            val h = (value / peak).toFloat() * size.height
            val barWidth = minOf(slot * 0.7f, 48.dp.toPx())
            drawRoundRect(
                color = accent.copy(alpha = if (index == clean.lastIndex) 1f else 0.65f),
                topLeft = androidx.compose.ui.geometry.Offset(index * slot + (slot - barWidth) / 2, size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(labels.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall)
        if (labels.size > 1) Text(labels.last(), style = MaterialTheme.typography.labelSmall)
    }
}

private fun inDashboardPeriod(timestamp: String, days: Int, cutoff: Instant): Boolean =
    days == 0 || runCatching { !Instant.parse(timestamp).isBefore(cutoff) }.getOrDefault(false)

private fun shortDate(timestamp: String): String =
    runCatching { java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.ITALY).withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(timestamp)) }.getOrDefault(timestamp.take(16))

private fun estimatedEnergyCost(kWh: Double?, tariff: Double?): String =
    if (kWh != null && tariff != null) fmt(kWh * tariff, "€") else "—"

private fun observedMinutes(first: String, last: String): Long? =
    runCatching { java.time.Duration.between(Instant.parse(first), Instant.parse(last)).toMinutes() }
        .getOrNull()?.takeIf { it > 0 }

@Composable
private fun PeriodPicker(days: Int, allowAll: Boolean = true, charging: Boolean = false, onChange: (Int) -> Unit) {
    val periods = if (charging) listOf(1 to "24h", 7 to "7g", 30 to "30g", 365 to "365g")
        else listOf(7 to "7g", 30 to "30g", 90 to "90g", 0 to "Tutto")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        periods.filter { allowAll || it.first != 0 }.forEach { (value, label) ->
            FilterChip(selected = value == days, onClick = { onChange(value) }, label = { Text(label) })
        }
    }
}

@Composable
internal fun MetricPair(leftLabel: String, left: String, rightLabel: String, right: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(leftLabel to left, rightLabel to right).forEach { (label, value) ->
            Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, color = accent)
            }
        }
    }
}

@Composable
private fun BatteryGauge(percent: Double?, accent: Color) {
    val valid = percent?.takeIf { it.isFinite() && it in 0.0..100.0 }
    Box(Modifier.fillMaxWidth().height(174.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(156.dp)) {
            val width = 12.dp.toPx()
            val offset = androidx.compose.ui.geometry.Offset(width / 2, width / 2)
            val arcSize = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(Color(0xFF424242), 135f, 270f, false, offset, arcSize, style = stroke)
            if (valid != null && valid > 0) drawArc(accent, 135f, (270 * valid / 100).toFloat(), false, offset, arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(valid?.let { fmt(it, "%") } ?: "—", style = MaterialTheme.typography.headlineLarge)
            Text("Carica residua", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BatteryHealthPanel(health: Double?, capacity: Double?, stale: Boolean, accent: Color) {
    val valid = health?.takeIf { it.isFinite() && it >= 0.0 }
    val tint = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else accent
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Salute batteria", style = MaterialTheme.typography.titleMedium)
        Text(valid?.let { fmt(it, "%") } ?: "N/D", style = MaterialTheme.typography.headlineLarge, color = tint)
        if (valid != null) {
            LinearProgressIndicator(progress = { (valid / 100.0).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth(), color = tint)
        }
        Text("Capacità nominale BMS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(capacity?.let { fmt(it, "kWh") } ?: "—", style = MaterialTheme.typography.titleLarge)
        Text(
            if (valid == null) "Riferimento BMS confrontabile non disponibile." else if (stale) "Ultima misura disponibile, non recente." else "Indicatore basato sui dati e sul riferimento disponibili.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
