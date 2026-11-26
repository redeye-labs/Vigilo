package io.github.redeyelabs.vigilo.ui

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
fun V2EnergyScreen(vm: MainViewModel, onBack: () -> Unit) {
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

    LaunchedEffect(paired, refresh) {
        if (!paired) return@LaunchedEffect
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val backend = Backend(vm.prefs)
                val capabilities = backend.v2Capabilities().modules
                val batteryOn = capabilities.any { it.id == "battery" && it.enabled }
                val chargingOn = capabilities.any { it.id == "charging" && it.enabled }
                val tripsOn = capabilities.any { it.id == "trips" && it.enabled }
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

    fun enabled(id: String) = modules.any { it.id == id && it.enabled }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Batteria e analisi", "Valori Tesla reali; stime sempre dichiarate.", onBack = onBack)
        if (!paired) {
            VigiloCard { Text("Collega prima il server Vigilo.") }
        } else {
            if (loading) Text("Aggiornamento dati in corso…")
            if (error != null) VigiloCard { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            listOf(
                Triple("battery", "Salute batteria", "Capacità BMS, energia residua, autonomia"),
                Triple("charging", "Ricariche e costi", "Energia immessa nella batteria e tariffa personale"),
                Triple("trips", "Viaggi e consumi", "Distanze osservate dal contachilometri"),
            ).forEach { (id, title, description) ->
                val active = enabled(id)
                VigiloCard {
                    Text(title, style = MaterialTheme.typography.titleMedium)
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
                            Text("Carica: " + (b.chargePercent?.let { fmt(it, "%") } ?: "Non disponibile"))
                            Text("Autonomia: " + (b.ratedRangeKm?.let { fmt(it, "km") } ?: "Non disponibile"))
                            Text("Capacità BMS: " + (b.fullPackKWh?.let { fmt(it, "kWh") } ?: "Non disponibile"))
                            Text("Energia residua: " + (b.remainingKWh?.let { fmt(it, "kWh") } ?: "Non disponibile"))
                            Text("Salute batteria: " + (b.healthPercent?.let { fmt(it, "%") } ?: "Non determinabile"))
                            Text(b.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } ?: Text("Nessuna misura disponibile.")
                        capacityTrend?.let { observation ->
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
                        Text("Sessioni osservate: ${charges.size}")
                        charges.takeLast(8).asReversed().forEach { session ->
                            Text("${fmt(session.batteryKWh, "kWh")} nella batteria · " +
                                (session.costEUR?.let { fmt(it, "€ stimati") } ?: "Costo non disponibile"))
                        }
                        if (charges.isEmpty()) Text("Nessuna ricarica ancora rilevata.")
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
                        Text("Tratti osservati: ${trips.size}")
                        trips.takeLast(8).asReversed().forEach { trip ->
                            Text(fmt(trip.distanceKm, "km") + " · Distanza da contachilometri")
                        }
                        if (trips.isEmpty()) Text("Nessun tratto ancora rilevato.")
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
