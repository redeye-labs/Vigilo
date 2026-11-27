package io.github.redeyelabs.vigilo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class Screen(val key: String) {
    HOME("auto"),
    NOTIFICATIONS("notifiche"),
    COMMANDS("comandi"),
    MAP("mappa"),
    CHARGE("ricarica"),
    SCHEDULE("programma"),
    ACTIONS("azioni"),
    HISTORY("storico"),
    SETTINGS("impostazioni"),
    GUIDE("guida"),
    LICENSES("licenze"),
    V2_HUB("vigilo2"),
    V2_ENERGY("energia2"),
    V2_BATTERY("batteria2"),
    V2_CHARGING("ricariche2"),
    V2_TRIPS("viaggi2"),
    V2_PARKING("parcheggi2"),
    V2_SECURITY("sicurezza2"),
    V2_COMMANDS("comandi2"),
    V2_AUTOMATIONS("automazioni2"),
    V2_BACKUP("backup2"),
    V2_DIAGNOSTICS("diagnostica2"),
}

@Composable
fun VigiloRoot(vm: MainViewModel, startTab: String?) {
    val loggedIn by vm.loggedIn.collectAsState()
    var screen by rememberSaveable {
        mutableStateOf(Screen.entries.firstOrNull { it.key == startTab } ?: if (loggedIn) Screen.HOME else Screen.GUIDE)
    }
    val busy by vm.busy.collectAsState()
    val progress by vm.progressText.collectAsState()
    val msg by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    val screenState = rememberSaveableStateHolder()
    val goHome = { screen = Screen.HOME }
    // Senza server Vigilo l'app non può mostrare l'auto: si apre la guida di abbinamento.
    LaunchedEffect(loggedIn) {
        if (!loggedIn && screen != Screen.GUIDE && screen != Screen.SETTINGS && screen != Screen.LICENSES) screen = Screen.GUIDE
    }
    DisposableEffect(screen) {
        vm.onHomeVisible(screen == Screen.HOME)
        onDispose { vm.onHomeVisible(false) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        // safeDrawing copre barra di stato E foro della fotocamera/notch: l'intestazione non finisce più
        // sotto la fotocamera. La barra di avanzamento occupa una sua riga e non copre il titolo.
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (busy) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        trackColor = MaterialTheme.colorScheme.surface,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (screen) {
                    Screen.HOME -> screenState.SaveableStateProvider(Screen.HOME.key) { StatusScreen(vm, onOpen = { screen = it }) }
                    Screen.NOTIFICATIONS -> NotificationsScreen(vm, onBack = goHome)
                    Screen.COMMANDS, Screen.V2_COMMANDS -> CommandsScreen(vm, onBack = goHome, onOpen = { screen = it })
                    Screen.MAP -> MapScreen(vm, onBack = goHome)
                    Screen.CHARGE -> ChargeScreen(vm, onBack = goHome)
                    Screen.SCHEDULE -> ScheduleScreen(vm, onBack = goHome)
                    Screen.ACTIONS -> ActionsScreen(vm, onBack = goHome)
                    Screen.HISTORY -> HistoryScreen(vm, onBack = goHome)
                    Screen.SETTINGS -> SettingsScreen(vm, onBack = goHome, onOpen = { screen = it })
                    Screen.GUIDE -> GuideScreen(vm, onBack = { screen = if (loggedIn) Screen.HOME else Screen.SETTINGS })
                    Screen.LICENSES -> LicensesScreen(onBack = { screen = Screen.SETTINGS })
                    Screen.V2_HUB -> V2HubScreen(vm, onBack = goHome, onOpen = { screen = it })
                    Screen.V2_ENERGY -> V2EnergyScreen(vm, onBack = goHome)
                    Screen.V2_BATTERY -> V2EnergyScreen(vm, onBack = goHome, onlyModule = "battery")
                    Screen.V2_CHARGING -> V2EnergyScreen(vm, onBack = goHome, onlyModule = "charging")
                    Screen.V2_PARKING -> V2EnergyScreen(vm, onBack = goHome, onlyModule = "parking")
                    Screen.V2_TRIPS -> V2EnergyScreen(vm, onBack = goHome, onlyModule = "trips")
                    Screen.V2_SECURITY -> V2HubScreen(vm, onBack = goHome, onOpen = { screen = it }, onlyModule = "security")
                    Screen.V2_AUTOMATIONS -> V2AutomationScreen(vm, onBack = goHome, onOpenSchedule = { screen = Screen.SCHEDULE })
                    Screen.V2_BACKUP -> V2BackupScreen(vm, onBack = goHome)
                    Screen.V2_DIAGNOSTICS -> V2DiagnosticsScreen(vm, onBack = goHome)
                }
                if (busy && !progress.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    ) {
                        Text(
                            progress!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
