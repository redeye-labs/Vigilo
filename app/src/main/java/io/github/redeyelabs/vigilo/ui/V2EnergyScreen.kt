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
    var modules by remember { mutableStateOf<List<Backend.V2Module>>(emptyList()) }
    var battery by remember { mutableStateOf<Backend.V2BatteryObservations?>(null) }
    var capacityTrend by remember { mutableStateOf<Backend.V2CapacityTrendResponse?>(null) }
    var charges by remember { mutableStateOf<List<Backend.V2ChargingSession>>(emptyList()) }
    var trips by remember { mutableStateOf<List<Backend.V2TripObservation>>(emptyList()) }
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
            modules = result.modules
            battery = result.battery
            capacityTrend = result.trend
            charges = result.charges
            trips = result.trips
            tariffInput = result.tariff?.toString()?.replace('.', ',').orEmpty()
            tariffSaved = result.tariff != null
        } catch (e: Exception) {
            error = e.message ?: "Impossibile leggere le statistiche."
        } finally {
            loading = false
        }
    }

    var days by remember { mutableIntStateOf(30) }
    val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
    val visibleCharges = charges.filter { inDashboardPeriod(it.lastSeen, days, cutoff) }
    val visibleTrips = trips.filter { inDashboardPeriod(it.lastSeen, days, cutoff) }

    fun enabled(id: String) = modules.any { it.id == id && it.enabled }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(when (onlyModule) { "battery" -> "Batteria"; "charging" -> "Ricariche"; "trips" -> "Viaggi"; else -> "Batteria e analisi" }, "Valori Tesla reali; stime sempre dichiarate.", onBack = onBack)
        if (!paired) {
            VigiloCard { Text("Collega prima il server Vigilo.") }
        } else {
            if (loading) Text("Aggiornamento dati in corso…")
            if (error != null) VigiloCard { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            listOf(
                Triple("battery", "Salute batteria", "Capacità BMS, energia residua, autonomia"),
                Triple("charging", "Ricariche e costi", "Energia immessa nella batteria e tariffa personale"),
                Triple("trips", "Viaggi e consumi", "Distanze osservate dal contachilometri"),
            ).filter { onlyModule == null || it.first == onlyModule }.forEach { (id, title, description) ->
                val active = enabled(id)
                VigiloCard {
                    val accent = when (id) { "battery" -> Color(0xFF30D158); "charging" -> Color(0xFF38BDF8); else -> Color(0xFFA78BFA) }
                    Text(title, style = MaterialTheme.typography.titleLarge, color = accent)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        enabled = !loading && !working,
                        onClick = {
                            working = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { Backend(vm.prefs).v2SetModule(id, !active) }
                                    refresh++
                                } catch (e: Exception) {
                                    error = e.message ?: "Impossibile modificare il modulo."
                                } finally {
                                    working = false
                                }
                            }
                        },
                    ) { Text(if (active) "Disattiva raccolta" else "Attiva raccolta") }

                    if (active && id == "battery") {
                        battery?.let { b ->
                            Spacer(Modifier.height(16.dp))
                            BatteryGauge(b.chargePercent, accent)
                            MetricPair("Autonomia nominale", b.ratedRangeKm?.let { fmt(it, "km") } ?: "—", "Energia residua", b.remainingKWh?.let { fmt(it, "kWh") } ?: "—", accent)
                            BatteryHealthPanel(b.healthPercent, b.fullPackKWh, b.stale, accent)
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
                            if (observation.available && observation.trend != null) {
                                val trend = observation.trend
                                Text("Variazione capacità BMS nel periodo: " + fmt(trend.percentChange, "%"))
                                Text("Capacità iniziale rilevata: " + fmt(trend.firstKWh, "kWh"))
                                Text("Capacità recente: " + fmt(trend.latestKWh, "kWh"))
                                EnergyBars(listOf(trend.firstKWh, trend.latestKWh), accent, "kWh", listOf("Iniziale", "Recente"))
                                Text(trend.note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(observation.note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (active && id == "charging") {
                        PeriodPicker(days) { days = it }
                        MetricPair("Energia osservata", if (visibleCharges.isEmpty()) "—" else fmt(visibleCharges.sumOf { it.batteryKWh }, "kWh"), "Costo stimato", if (visibleCharges.isNotEmpty() && visibleCharges.all { it.costEUR != null }) fmt(visibleCharges.sumOf { it.costEUR ?: 0.0 }, "€") else "—", accent)
                        MetricPair("Sessioni osservate", visibleCharges.size.toString(), "Energia media", if (visibleCharges.isEmpty()) "—" else fmt(visibleCharges.map { it.batteryKWh }.average(), "kWh"), accent)
                        Text("Cronologia ricariche", style = MaterialTheme.typography.titleMedium)
                        EnergyBars(visibleCharges.takeLast(12).map { it.batteryKWh }, accent, "kWh", visibleCharges.takeLast(12).map { shortDate(it.lastSeen) })
                        visibleCharges.takeLast(8).asReversed().forEach { session ->
                            MetricPair(shortDate(session.lastSeen), fmt(session.batteryKWh, "kWh"), "Costo stimato", session.costEUR?.let { fmt(it, "€") } ?: "—", accent)
                        }
                        if (visibleCharges.isEmpty()) Text("Nessuna ricarica osservata nel periodo.")
                        Text(
                            "La telemetria può essere incompleta. I kWh nella batteria non coincidono necessariamente con quelli fatturati.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = tariffInput,
                            onValueChange = { tariffInput = it.take(12); tariffSaved = false },
                            label = { Text("Tariffa personale (€/kWh)") },
                            placeholder = { Text("Facoltativa") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            enabled = !working && (tariffInput.isBlank() ||
                                tariffInput.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10.0 } == true),
                            onClick = {
                                working = true
                                scope.launch {
                                    try {
                                        val tariff = tariffInput.replace(',', '.').toDoubleOrNull()
                                        withContext(Dispatchers.IO) { Backend(vm.prefs).v2SetChargingTariff(tariff) }
                                        tariffSaved = true
                                        refresh++
                                    } catch (e: Exception) {
                                        error = e.message ?: "Tariffa non salvata."
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                        ) { Text(if (tariffSaved) "Tariffa salvata" else "Salva tariffa") }
                    }
                    if (active && id == "trips") {
                        PeriodPicker(days) { days = it }
                        MetricPair("Distanza osservata", if (visibleTrips.isEmpty()) "—" else fmt(visibleTrips.sumOf { it.distanceKm }, "km"), "Tratti osservati", visibleTrips.size.toString(), accent)
                        MetricPair("Distanza media per tratto", if (visibleTrips.isEmpty()) "—" else fmt(visibleTrips.map { it.distanceKm }.average(), "km"), "Tratto più lungo", visibleTrips.maxOfOrNull { it.distanceKm }?.let { fmt(it, "km") } ?: "—", accent)
                        Text("Cronologia percorrenze", style = MaterialTheme.typography.titleMedium)
                        EnergyBars(visibleTrips.takeLast(12).map { it.distanceKm }, accent, "km", visibleTrips.takeLast(12).map { shortDate(it.lastSeen) })
                        visibleTrips.takeLast(8).asReversed().forEach { trip ->
                            MetricPair(shortDate(trip.lastSeen), fmt(trip.distanceKm, "km"), "Fonte", "Odometro", accent)
                        }
                        if (visibleTrips.isEmpty()) Text("Nessun tratto osservato nel periodo.")
                        Text(
                            "Non sono viaggi GPS completi. Consumo in kWh/100 km non calcolato senza energia misurata sullo stesso intervallo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth(), enabled = !working) {
                Text("Aggiorna statistiche")
            }
        }
    }
}

private data class EnergyScreenData(
    val modules: List<Backend.V2Module>,
    val battery: Backend.V2BatteryObservations?,
    val trend: Backend.V2CapacityTrendResponse?,
    val charges: List<Backend.V2ChargingSession>,
    val trips: List<Backend.V2TripObservation>,
    val tariff: Double?,
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
            drawRoundRect(
                color = accent.copy(alpha = if (index == clean.lastIndex) 1f else 0.65f),
                topLeft = androidx.compose.ui.geometry.Offset(index * slot + slot * 0.15f, size.height - h),
                size = androidx.compose.ui.geometry.Size(slot * 0.7f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(labels.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall)
        Text(labels.lastOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall)
    }
}

private fun inDashboardPeriod(timestamp: String, days: Int, cutoff: Instant): Boolean =
    days == 0 || runCatching { !Instant.parse(timestamp).isBefore(cutoff) }.getOrDefault(false)

private fun shortDate(timestamp: String): String =
    runCatching { java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.ITALY).withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(timestamp)) }.getOrDefault(timestamp.take(16))

@Composable
private fun PeriodPicker(days: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(7 to "7g", 30 to "30g", 90 to "90g", 0 to "Tutto").forEach { (value, label) ->
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
            drawArc(Color(0xFF37373F), 135f, 270f, false, offset, arcSize, style = stroke)
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
