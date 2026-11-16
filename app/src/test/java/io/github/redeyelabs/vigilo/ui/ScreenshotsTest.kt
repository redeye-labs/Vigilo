package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
                    last24 = last24,
                    alerts24 = sentryAlertCount(last24),
                    buckets = hourlyEventBuckets(last24, now),
                    notificationsSubtitle = notificationsSubtitle(true, true, true, true),
                    actionsSubtitle = actionsSubtitle(false, false),
                    onRefresh = {},
                    onPullRefresh = {},
                    onToggleSentry = {},
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
                    url = "http://homeassistant.local/local/vigilo/signed-config.jws",
                    onUrlChange = {},
                    expEpochSeconds = 1_826_274_221L,
                    autoRenew = true,
                    onAutoRenewChange = {},
                    busy = false,
                    progress = null,
                    status = null,
                    onActivate = {},
                    onCheckStatus = {},
                    onBack = {},
                    alertUnlocked = true,
                    unlockedMinutes = 10,
                    alertWindows = true,
                    alertChargeComplete = true,
                    alertLowBattery = true,
                    lowBatteryPct = 20,
                )
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
