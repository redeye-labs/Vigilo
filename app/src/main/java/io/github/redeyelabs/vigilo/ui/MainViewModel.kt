package io.github.redeyelabs.vigilo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.api.FleetTelemetrySupport
import io.github.redeyelabs.vigilo.api.Snapshot
import io.github.redeyelabs.vigilo.api.TeslaApi
import io.github.redeyelabs.vigilo.api.VehicleCommandSupport
import io.github.redeyelabs.vigilo.api.TeslaAuth
import io.github.redeyelabs.vigilo.api.VehicleInfo
import io.github.redeyelabs.vigilo.data.AppDatabase
import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.data.ScheduleEntity
import io.github.redeyelabs.vigilo.work.Monitor
import io.github.redeyelabs.vigilo.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs.get(app)
    private val db = AppDatabase.get(app)

    val snapshot = MutableStateFlow(Monitor.lastSnapshot(prefs))
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val progressText = MutableStateFlow<String?>(null)
    val pendingAction = MutableStateFlow<String?>(null)
    val pullRefreshing = MutableStateFlow(false)
    val autoRefreshSeconds = MutableStateFlow(prefs.autoRefreshSeconds)
    val loggedIn = MutableStateFlow(prefs.isLoggedIn)
    val vehicles = MutableStateFlow<List<VehicleInfo>>(emptyList())
    val proxyFingerprint = MutableStateFlow(prefs.proxyCertSha256)
    val proxyUrl = MutableStateFlow(prefs.proxyUrl)
    val telemetryJwsUrl = MutableStateFlow(prefs.telemetryJwsUrl.ifBlank { FleetTelemetrySupport.defaultJwsUrl(prefs.proxyUrl) })
    val telemetryExp = MutableStateFlow(prefs.telemetryJwsExp.takeIf { it > 0 })
    val telemetryAutoRenew = MutableStateFlow(prefs.telemetryAutoRenew)
    val telemetryBusy = MutableStateFlow(false)
    val telemetryMessage = MutableStateFlow<String?>(null)
    val telemetryProgress = MutableStateFlow<String?>(null)


    val events: StateFlow<List<EventEntity>> =
        db.events().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val schedules: StateFlow<List<ScheduleEntity>> =
        db.schedules().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toast(m: String) { message.value = m }

    private var homeVisible = false
    private var appForeground = false
    private var homeRefreshJob: Job? = null
    private var lastRefreshAt = 0L

    fun onAppResume() {
        appForeground = true
        refreshSilent()
        syncHomeRefreshLoop()
    }

    fun onAppPause() {
        appForeground = false
        syncHomeRefreshLoop()
    }

    fun onHomeVisible(visible: Boolean) {
        val appeared = visible && !homeVisible
        homeVisible = visible
        if (appeared && appForeground) refreshSilent()
        syncHomeRefreshLoop()
    }

    fun setAutoRefreshSeconds(seconds: Int) {
        prefs.autoRefreshSeconds = seconds
        autoRefreshSeconds.value = prefs.autoRefreshSeconds
        syncHomeRefreshLoop()
    }

    private fun syncHomeRefreshLoop() {
        homeRefreshJob?.cancel()
        homeRefreshJob = null
        val interval = autoRefreshSeconds.value
        val run = homeVisible && appForeground && interval > 0 && prefs.isLoggedIn && prefs.vin.isNotBlank()
        if (!run) return
        homeRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(interval * 1000L)
                if (pendingAction.value != null) continue
                refreshSilent(skipIfRecent = false)
            }
        }
    }

    /** Lettura silenziosa: se l'auto dorme interroga solo lo stato, senza svegliarla. */
    private fun refreshSilent(skipIfRecent: Boolean = true) {
        if (!prefs.isLoggedIn || prefs.vin.isBlank()) return
        if (skipIfRecent && System.currentTimeMillis() - lastRefreshAt < 30_000L) return
        viewModelScope.launch(Dispatchers.IO) {
            Monitor.runOnce(getApplication(), force = true)
                .onSuccess {
                    snapshot.value = it
                    lastRefreshAt = System.currentTimeMillis()
                }
        }
    }

    fun refresh() = io {
        Monitor.runOnce(getApplication(), force = true, fresh = true)
            .onSuccess {
                snapshot.value = it
                lastRefreshAt = System.currentTimeMillis()
            }
            .onFailure { toast(it.message ?: "Errore") }
    }

    fun pullRefresh() {
        if (pullRefreshing.value || pendingAction.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            pullRefreshing.value = true
            try {
                Monitor.runOnce(getApplication(), force = true, fresh = true)
                    .onSuccess {
                        snapshot.value = it
                        lastRefreshAt = System.currentTimeMillis()
                    }
                    .onFailure { toast(it.message ?: "Errore") }
            } finally {
                pullRefreshing.value = false
            }
        }
    }

    fun toggleLock() {
        val locked = snapshot.value?.locked == true
        if (locked) command("Sblocco auto", "lock", backend = "unlock") { a, v -> a.unlock(v) }
        else command("Chiusura auto", "lock", backend = "lock") { a, v -> a.lock(v) }
    }

    fun handleCallback(code: String, state: String?) = io {
        runCatching { TeslaAuth.exchangeCode(prefs, code, state) }
            .onSuccess { loggedIn.value = true; toast("Collegato a Tesla ✔"); loadVehicles() }
            .onFailure { toast(it.message ?: "Login non riuscito") }
    }

    fun loadVehicles() = io {
        runCatching { TeslaApi(prefs).listVehicles() }
            .onSuccess {
                vehicles.value = it
                if (prefs.vin.isBlank() && it.size == 1) selectVehicle(it.first())
            }
            .onFailure { toast(it.message ?: "Errore elenco veicoli") }
    }

    fun selectVehicle(v: VehicleInfo) {
        prefs.vin = v.vin; prefs.vehicleName = v.name
        toast("Veicolo selezionato: ${v.name}")
    }

    fun logout() { prefs.logout(); loggedIn.value = prefs.isLoggedIn; snapshot.value = null }

    fun verifyProxy(rawUrl: String) = io {
        runCatching { TeslaApi(prefs).verifyProxy(rawUrl) }
            .onSuccess {
                proxyUrl.value = prefs.proxyUrl
                proxyFingerprint.value = prefs.proxyCertSha256
                toast(it)
            }
            .onFailure { toast(it.message ?: "Verifica del proxy non riuscita") }
    }

    fun resetProxyCertificate() {
        prefs.proxyCertSha256 = ""
        proxyFingerprint.value = ""
        toast("Certificato proxy reimpostato. Premi «Collega proxy» per associarne uno nuovo.")
    }

    fun commitProxyUrl(rawUrl: String) {
        val previous = prefs.proxyUrl
        prefs.proxyUrl = rawUrl
        proxyUrl.value = prefs.proxyUrl
        proxyFingerprint.value = prefs.proxyCertSha256
        if (prefs.telemetryJwsUrl.isBlank()) {
            telemetryJwsUrl.value = FleetTelemetrySupport.defaultJwsUrl(prefs.proxyUrl)
        }
        if (previous != prefs.proxyUrl && previous.isNotBlank()) {
            toast("Indirizzo aggiornato. Il certificato precedente è stato cancellato: premi «Collega proxy».")
        }
    }

    fun command(
        label: String,
        action: String? = null,
        backend: String? = null,
        block: suspend (TeslaApi, String) -> String,
    ) = io {
        pendingAction.value = action
        progressText.value = null
        try {
            Monitor.manualCommand(getApplication(), label, onProgress = { progressText.value = it }, backendCommand = backend, block = block)
                .onSuccess {
                    toast(it)
                    runCatching { Monitor.runOnce(getApplication(), force = true).getOrThrow() }
                        .onSuccess { snap ->
                            snapshot.value = snap
                            lastRefreshAt = System.currentTimeMillis()
                        }
                }
                .onFailure { toast(VehicleCommandSupport.userMessage(it)) }
        } finally {
            pendingAction.value = null
            progressText.value = null
        }
    }

    fun setSentry(on: Boolean) {
        prefs.expectedSentryChangeUntil = System.currentTimeMillis() + 30 * 60_000L
        command(
            if (on) "Attiva Sentinella" else "Disattiva Sentinella",
            action = "sentry",
            backend = if (on) "sentry_on" else "sentry_off",
        ) { api, vin -> api.setSentry(vin, on) }
    }

    fun clearHistory() = io { db.events().clear() }

    fun saveSchedule(s: ScheduleEntity) = io {
        val id = if (s.id == 0L) db.schedules().insert(s) else { db.schedules().update(s); s.id }
        Scheduler.scheduleRule(getApplication(), s.copy(id = id))
    }

    fun deleteSchedule(s: ScheduleEntity) = io {
        db.schedules().delete(s); Scheduler.cancelRule(getApplication(), s.id)
    }

    fun applyMonitoring() {
        Scheduler.applyPolling(getApplication())
        Scheduler.applyIntensive(getApplication())
    }


    fun commitTelemetryJwsUrl(raw: String) {
        val url = raw.trim()
        prefs.telemetryJwsUrl = url
        // Si salva esattamente quello che l'utente scrive o incolla: niente ripristino del valore
        // predefinito mentre il campo è vuoto (era la causa del "torna all'indirizzo di default").
        telemetryJwsUrl.value = url
    }

    fun setTelemetryAutoRenew(on: Boolean) {
        prefs.telemetryAutoRenew = on
        telemetryAutoRenew.value = on
        Scheduler.applyTelemetryRenew(getApplication())
    }

    fun refreshTelemetryExpiry() {
        val url = telemetryJwsUrl.value.ifBlank { FleetTelemetrySupport.defaultJwsUrl(prefs.proxyUrl) }
        if (url.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TeslaApi(prefs).fetchSignedConfigJws(url) }
                .onSuccess { telemetryExp.value = prefs.telemetryJwsExp.takeIf { e -> e > 0 } }
        }
    }

    fun activateSentryAlerts() = io {
        val url = telemetryJwsUrl.value.ifBlank { FleetTelemetrySupport.defaultJwsUrl(prefs.proxyUrl) }
        commitTelemetryJwsUrl(url)
        telemetryBusy.value = true
        telemetryProgress.value = null
        telemetryMessage.value = null
        try {
            val api = TeslaApi(prefs).also { it.onProgress = { telemetryProgress.value = it } }
            val msg = api.enableSentryAlerts(prefs.vin, url)
            telemetryExp.value = prefs.telemetryJwsExp.takeIf { it > 0 }
            telemetryMessage.value = msg
            toast(msg)
        } catch (e: Exception) {
            val msg = e.message ?: "Attivazione non riuscita"
            telemetryMessage.value = msg
            toast(msg)
        } finally {
            telemetryBusy.value = false
            telemetryProgress.value = null
        }
    }

    fun checkTelemetryStatus() = io {
        if (prefs.vin.isBlank()) {
            telemetryMessage.value = "Seleziona un veicolo in Impostazioni."
            return@io
        }
        telemetryBusy.value = true
        telemetryProgress.value = "Controllo dello stato…"
        try {
            val msg = TeslaApi(prefs).telemetryStatusMessage(prefs.vin)
            telemetryMessage.value = msg
        } catch (e: Exception) {
            telemetryMessage.value = e.message ?: "Controllo non riuscito"
        } finally {
            telemetryBusy.value = false
            telemetryProgress.value = null
        }
    }

    // ---------------- Home Assistant (componente aggiuntivo) ----------------

    val backendMode = MutableStateFlow(prefs.backendMode)
    val backendUrl = MutableStateFlow(prefs.backendUrl)
    val backendChecks = MutableStateFlow<List<Backend.Check>>(emptyList())
    val backendChecksLoading = MutableStateFlow(false)
    val backendChecksError = MutableStateFlow<String?>(null)
    val backendSettings = MutableStateFlow<Backend.SettingsResponse?>(null)

    /** Abbinamento dal QR o dal link incollato (vigilo://pair?url=…&token=…). */
    fun pairBackend(raw: String, onDone: (Boolean) -> Unit = {}) = io {
        val pairing = Backend.parsePairing(raw)
        if (pairing == null) {
            toast("Codice non valido: scansiona il QR mostrato nel componente aggiuntivo Vigilo.")
            onDone(false)
            return@io
        }
        runCatching { Backend(pairing.url, pairing.token).status() }
            .onSuccess { st ->
                prefs.backendUrl = pairing.url
                prefs.backendToken = pairing.token
                prefs.backendLastEventId = 0
                if (st.vin.isNotBlank()) prefs.vin = st.vin
                if (st.name.isNotBlank()) prefs.vehicleName = st.name
                backendMode.value = true
                backendUrl.value = prefs.backendUrl
                loggedIn.value = true
                snapshot.value = Backend.toSnapshot(st)
                Scheduler.applyTelemetryRenew(getApplication())
                toast(if (st.vin.isBlank()) "Abbinato a Home Assistant ✔ Scegli l'auto nel componente aggiuntivo." else "Abbinato a Home Assistant ✔")
                runCatching { Monitor.syncBackendEvents(getApplication()) }
                onDone(true)
            }
            .onFailure { toast(it.message ?: "Abbinamento non riuscito"); onDone(false) }
    }

    fun unpairBackend() {
        prefs.unpairBackend()
        backendMode.value = false
        backendUrl.value = ""
        backendChecks.value = emptyList()
        backendSettings.value = null
        loggedIn.value = prefs.isLoggedIn
        snapshot.value = null
        toast("Home Assistant scollegato.")
    }

    /** Controlli dal vivo dell'add-on (Tailscale, Funnel, login Tesla, chiave, telemetria…). */
    fun loadBackendChecks(deep: Boolean = true) {
        if (!prefs.backendMode || backendChecksLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            backendChecksLoading.value = true
            backendChecksError.value = null
            try {
                backendChecks.value = Backend(prefs).health(deep)
            } catch (e: Exception) {
                backendChecksError.value = e.message ?: "Controllo non riuscito"
            } finally {
                backendChecksLoading.value = false
            }
        }
    }

    fun loadBackendSettings() {
        if (!prefs.backendMode) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Backend(prefs).settings() }.onSuccess { backendSettings.value = it }
        }
    }

    fun saveBackendSettings(transform: (Backend.Settings) -> Backend.Settings) {
        val cur = backendSettings.value ?: return
        val next = transform(cur.settings)
        backendSettings.value = cur.copy(settings = next)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Backend(prefs).saveSettings(next) }
                .onSuccess { backendSettings.value = it }
                .onFailure { toast(it.message ?: "Impostazioni non salvate") }
        }
    }

    fun syncEvents() {
        if (!prefs.backendMode) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { Monitor.syncBackendEvents(getApplication()) } }
    }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        busy.value = true
        try { block() } finally { busy.value = false }
    }

    @Suppress("unused") private fun keep(s: Snapshot) = s
}
