package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.redeyelabs.vigilo.api.Backend
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.github.redeyelabs.vigilo.api.Snapshot
import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.EventType
import java.time.Instant
import org.junit.Rule
import org.junit.Test

/** Schermate a dimensione Pixel 7 (1080×2400), tema scuro, dati di esempio. */
class ScreenshotsTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = PIXEL_7,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun home() {
        val now = NOW
        val events = sampleEvents(now)
        val last24 = eventsInLast24h(events, now)
        paparazzi.snapshot(name = "home") {
            PhoneShell {
                StatusScreenContent(
                    name = "Model 3",
                    snapshot = sampleSnapshot(now),
                    ready = true,
                    now = now,
                    showHero = false,
                    pendingSentry = false,
                    pullRefreshing = false,
                    sessionEvents = last24.size,
                    sessionAlerts = sentryAlertCount(last24),
                    sessionStartedAt = now - 6 * 3_600_000L,
                    monitoringEnabled = true,
                    notificationsSubtitle = NOTIFICATIONS_SUBTITLE,
                    onRefresh = {},
                    onPullRefresh = {},
                    onToggleSentry = {},
                    onToggleMonitoring = {},
                    onOpen = {},
                )
            }
        }
    }

    @Test
    fun notifiche() {
        paparazzi.snapshot(name = "notifiche") {
            PhoneShell {
                NotificationsScreenContent(
                    paired = false,
                    settings = null,
                    onChange = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun azioni() {
        paparazzi.snapshot(name = "azioni") {
            PhoneShell {
                ActionsScreenContent(
                    state = MainViewModel.ActionsState.Ready(
                        Backend.Actions(enabled = true, onAware = true, onPanic = true, flash = true, honk = false, hoursOnly = true),
                    ),
                    onChange = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun programma() {
        paparazzi.snapshot(name = "programma") {
            PhoneShell {
                ScheduleScreenContent(
                    rules = listOf(
                        Backend.Rule(id = 1, hour = 7, minute = 0, daysMask = 0b0011111, enable = false, active = true),
                        Backend.Rule(id = 2, hour = 22, minute = 30, daysMask = 0b1111111, enable = true, active = true),
                        Backend.Rule(id = 3, hour = 9, minute = 15, daysMask = 0b1100000, enable = true, active = false),
                    ),
                    where = MainViewModel.ScheduleWhere.SERVER,
                    onSave = {},
                    onDelete = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun programmaVuoto() {
        paparazzi.snapshot(name = "programma_vuoto") {
            PhoneShell {
                ScheduleScreenContent(rules = emptyList(), where = MainViewModel.ScheduleWhere.SERVER, onSave = {}, onDelete = {}, onBack = {})
            }
        }
    }

    @Test
    fun guida() {
        paparazzi.snapshot(name = "guida") {
            PhoneShell {
                GuideScreenContent(
                    paired = true,
                    backendUrl = "https://vigilo.tailnet-example.ts.net",
                    checks = sampleChecks(),
                    loading = false,
                    error = null,
                    onBack = {},
                    onCopyRepo = {},
                    onScan = {},
                    onPair = {},
                    onRecheck = {},
                )
            }
        }
    }

    @Test
    fun guidaDaAbbinare() {
        paparazzi.snapshot(name = "guida-abbinamento") {
            PhoneShell {
                GuideScreenContent(
                    paired = false,
                    backendUrl = "",
                    checks = emptyList(),
                    loading = false,
                    error = null,
                    onBack = {},
                    onCopyRepo = {},
                    onScan = {},
                    onPair = {},
                    onRecheck = {},
                )
            }
        }
    }

    @Test
    fun impostazioni() {
        paparazzi.snapshot(name = "impostazioni") {
            PhoneShell {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScreenHeader("Impostazioni", "Home Assistant, avvisi e preferenze.", onBack = {})
                    HomeAssistantSettings(
                        paired = true,
                        backendUrl = "https://vigilo.tailnet-example.ts.net",
                        settings = Backend.SettingsResponse(
                            ok = true,
                            settings = Backend.Settings(notifyTargets = listOf("mobile_app_telefono")),
                            availableTargets = listOf("mobile_app_telefono"),
                        ),
                        onGuide = {},
                        onUnpair = {},
                        onChange = {},
                    )
                    PrefToggle("Mostra la foto dell'auto", false) {}
                    PrefToggle("Esempio interruttore acceso", true) {}
                }
            }
        }
    }

    @Test
    fun batteria() = energyScreenshot("batteria", "battery")

    @Test
    fun saluteBatteria() = energyScreenshot("salute_batteria", "battery", scroll = 900)

    @Test
    fun ricariche() = energyScreenshot("ricariche", "charging")

    @Test
    fun viaggi() = energyScreenshot("viaggi", "trips")

    @Test
    fun parcheggi() = energyScreenshot("parcheggi", "parking")

    private fun energyScreenshot(name: String, module: String, scroll: Int = 0) {
        val now = Instant.parse("2026-11-27T12:00:00Z")
        val readings = listOf(74.9, 75.2, 74.8, 74.6, 74.7, 74.6)
        val first = "2026-11-27T09:10:00Z"
        val last = "2026-11-27T09:25:00Z"
        val data = EnergyScreenData(
            modules = listOf("battery", "charging", "trips").map { Backend.V2Module(it, true, true) },
            battery = Backend.V2BatteryObservations(
                fullPackKWh = 74.6, remainingKWh = 46.1, ratedRangeKm = 263.3, chargePercent = 62.0,
                tempMinC = 19.0, tempMaxC = 22.0, equivalentChargeCycles = 31.0,
                ratedFullRangeKm = 424.7, latestReceivedAt = last,
            ),
            trend = Backend.V2CapacityTrendResponse(
                available = true,
                trend = Backend.V2CapacityTrend(firstKWh = 75.0, latestKWh = 74.6, percentChange = -0.53),
                history = readings.mapIndexed { index, value ->
                    Backend.V2BatteryHistoryPoint(now.minusSeconds((6L - index) * 604_800).toString(), value)
                },
            ),
            charges = listOf(
                Backend.V2ChargingSession(firstSeen = "2026-11-26T07:55:00Z",
                    lastSeen = "2026-11-26T08:25:00Z", batteryKWh = 14.2, costEUR = 4.26),
                Backend.V2ChargingSession(firstSeen = "2026-11-24T06:10:00Z",
                    lastSeen = "2026-11-24T07:35:00Z", batteryKWh = 10.5, costEUR = 3.15),
            ),
            trips = listOf(Backend.V2TripObservation(firstSeen = first, lastSeen = last, distanceKm = 17.0)),
        )
        val report = Backend.V2ObservedEnergy(
            driving = listOf(Backend.V2ObservedDrive(first, last, 2.8, 17.0)),
            parking = listOf(Backend.V2ObservedParking(last, "2026-11-27T09:40:00Z", 0.2, "on")),
            drivingKWh = 2.8, drivingKm = 17.0, whPerKm = 164.7,
            parkedKWh = 0.2, parkedHours = 0.25, sentryKWh = 0.2, tariffEURPerKWh = 0.30,
            note = "Consumi stimati sugli intervalli osservati.",
        )
        paparazzi.snapshot(name = name) {
            PhoneShell {
                EnergyScreenContent(
                    data = data, energyReport = report, onlyModule = module,
                    paired = true, days = 30, loading = false, working = false,
                    error = null, energyError = null, tariffInput = "0,30", tariffSaved = true,
                    onPeriodChange = {}, onEditTariff = {}, onSetModule = { _, _ -> },
                    onSaveTariff = {}, onRefresh = {}, onBack = {}, now = now,
                    scrollState = rememberScrollState(scroll),
                )
            }
        }
    }

    @Test
    fun pneumatici() {
        val readings = listOf(
            "TpmsPressureFl" to 2.90, "TpmsPressureFr" to 2.89,
            "TpmsPressureRl" to 2.92, "TpmsPressureRr" to 2.91,
        ).map { (signal, pressure) ->
            Backend.V2Measurement(signal = signal, value = pressure, unit = "bar", quality = "valid")
        }
        paparazzi.snapshot(name = "pneumatici") {
            PhoneShell {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    ScreenHeader("Diagnostica Vigilo", "Connessione e segnali ricevuti dalla Tesla.", onBack = {})
                    VigiloCard {
                        Text("Pressione pneumatici", style = MaterialTheme.typography.titleMedium)
                        TireLayout(readings)
                    }
                }
            }
        }
    }

    @Test
    fun icona() {
        paparazzi.snapshot(name = "icona") {
            Box(Modifier.size(216.dp).background(Color(0xFF0B0B0C))) {
                Image(painterResource(io.github.redeyelabs.vigilo.R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
        }
    }

    companion object {
        // Pixel 7: 1080×2400, ~416 dpi (stesse misure del Pixel 6, densità 420).
        val PIXEL_7: DeviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = 1080,
            screenHeight = 2400,
            xdpi = 416,
            ydpi = 416,
        )
        const val NOW = 1_762_280_400_000L // 4 novembre 2025, 18:00 CET circa (fisso per le immagini)
    }
}

@Composable
private fun PhoneShell(content: @Composable () -> Unit) {
    VigiloTheme {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
                content()
            }
        }
    }
}

private fun sampleChecks() = listOf(
    Backend.Check("tailscale", "Tailscale collegato", true),
    Backend.Check("funnel", "Funnel attivo (443 e 8443)", true),
    Backend.Check("mqtt", "Broker MQTT", true),
    Backend.Check("telemetry_server", "Server telemetria avviato", true),
    Backend.Check("login", "Accesso Tesla", true),
    Backend.Check("pubkey", "Chiave pubblica visibile da internet", true),
    Backend.Check("partner", "Dominio registrato presso Tesla", true),
    Backend.Check("vehicle", "Auto selezionata", true, "Model 3"),
    Backend.Check("key", "Chiave virtuale associata all'auto", true),
    Backend.Check("config", "Configurazione telemetria sull'auto", true, "scade il 15/11/2027"),
    Backend.Check("car", "L'auto ha inviato dati", false, "in attesa"),
)

private fun sampleSnapshot(now: Long) = Snapshot(
    timestamp = now - 3 * 60_000L,
    online = true,
    connectionState = "online",
    name = "Model 3",
    batteryLevel = 72,
    rangeKm = 320.0,
    chargingState = "Disconnected",
    locked = true,
    sentry = true,
    sentryMode = "Armed",
    shiftState = "P",
)

private fun sampleEvents(now: Long) = listOf(
    EventEntity(
        id = 1,
        timestamp = now - 2 * 3_600_000L,
        type = EventType.SENTRY_AWARE,
        title = "Qualcuno vicino all'auto",
        message = "Sentinella in allerta alle 16:02.",
    ),
    EventEntity(
        id = 2,
        timestamp = now - 5 * 3_600_000L,
        type = EventType.UNLOCKED,
        title = "Auto lasciata aperta",
        message = "L'auto risulta aperta da più di 10 minuti",
    ),
)
