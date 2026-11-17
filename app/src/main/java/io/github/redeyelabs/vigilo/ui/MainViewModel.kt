package io.github.redeyelabs.vigilo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.api.Snapshot
import io.github.redeyelabs.vigilo.api.VehicleCommandSupport
import io.github.redeyelabs.vigilo.data.AppDatabase
import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.data.ScheduleEntity
import io.github.redeyelabs.vigilo.work.Monitor
import io.github.redeyelabs.vigilo.work.Scheduler
import kotlinx.coroutines.Dispatchers
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
    val loggedIn = MutableStateFlow(prefs.isLoggedIn)


    val events: StateFlow<List<EventEntity>> =
        db.events().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val schedules: StateFlow<List<ScheduleEntity>> =
        db.schedules().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toast(m: String) { message.value = m }

    private var homeVisible = false
    private var appForeground = false
    private var lastRefreshAt = 0L

    fun onAppResume() {
        appForeground = true
        // Riporta su il servizio se il sistema l'ha fermato (idempotente se è già attivo).
        Scheduler.applyIntensive(getApplication())
        refreshSilent()
    }

    fun onAppPause() {
        appForeground = false
    }

    fun onHomeVisible(visible: Boolean) {
        val appeared = visible && !homeVisible
        homeVisible = visible
        if (appeared && appForeground) refreshSilent()
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
        if (locked) command("lock", backend = "unlock") else command("lock", backend = "lock")
    }

    /** Comando tramite il server Vigilo; [action] serve all'interfaccia per mostrare l'attesa. */
    fun command(action: String?, backend: String) = io {
        pendingAction.value = action
        progressText.value = null
        try {
            Monitor.manualCommand(getApplication(), onProgress = { progressText.value = it }, backendCommand = backend)
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
        command(action = "sentry", backend = if (on) "sentry_on" else "sentry_off")
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
        Scheduler.applyIntensive(getApplication(), restartLoop = true)
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
            toast("Codice non valido: inquadra il QR mostrato nella pagina di configurazione di Vigilo.")
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
                Scheduler.applyPolling(getApplication())
                Scheduler.applyIntensive(getApplication())
                toast(if (st.vin.isBlank()) "Collegato a Vigilo ✔ Scegli l'auto nella pagina di configurazione di Vigilo." else "Collegato a Vigilo ✔")
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
        Scheduler.applyIntensive(getApplication())
        toast("Scollegato dal server Vigilo.")
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
