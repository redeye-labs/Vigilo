package io.github.redeyelabs.vigilo.work

import android.content.Context
import android.location.Location
import io.github.redeyelabs.vigilo.api.ApiException
import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.api.Snapshot
import io.github.redeyelabs.vigilo.api.TeslaApi
import io.github.redeyelabs.vigilo.data.AppDatabase
import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.EventType
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.notify.Notifier
import io.github.redeyelabs.vigilo.widget.VigiloWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Logica di un singolo controllo. Usata dal lavoro periodico (WorkManager), dal servizio in primo piano
 * e dal pulsante "Aggiorna".
 *
 * NOTA: la Fleet API REST non espone un evento "la Sentinella ha rilevato qualcosa". Lo stato
 * SentryMode = Aware/Panic esiste solo in Fleet Telemetry (serve un server). Qui l'evento sospetto viene
 * dedotto confrontando due letture consecutive mentre la Sentinella è attiva: apertura porte/bagagliaio,
 * sblocco, spostamento, Sentinella spenta senza che l'abbia chiesto l'app, in assenza del conducente.
 */
object Monitor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    fun lastSnapshot(p: Prefs): Snapshot? =
        p.lastSnapshotJson.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<Snapshot>(it) }.getOrNull() }

    /** @param force se true legge i dati anche quando la Sentinella è spenta (aggiornamento manuale). */
    suspend fun runOnce(ctx: Context, force: Boolean = false, fresh: Boolean = false): Result<Snapshot?> = mutex.withLock {
        val p = Prefs.get(ctx)
        if (p.backendMode) return runBackend(ctx, p, fresh)
        if (!p.isLoggedIn || p.vin.isBlank()) return Result.failure(IllegalStateException("Configura login e veicolo nelle impostazioni"))
        val api = TeslaApi(p)
        val db = AppDatabase.get(ctx)
        val prev = lastSnapshot(p)
        try {
            val conn = api.connectionState(p.vin)
            if (conn != "online") {
                // Non svegliamo l'auto: costerebbe una "wake" e consumerebbe batteria.
                val s = (prev ?: Snapshot()).copy(timestamp = System.currentTimeMillis(), online = false, connectionState = conn)
                save(ctx, p, s)
                return Result.success(s)
            }
            if (!force && p.onlyWhenSentry && prev != null && prev.sentry == false) {
                // Con "solo con Sentinella attiva" leggiamo comunque se l'auto è online ma l'ultima lettura
                // è vecchia di oltre 1 ora, per non perdere ricarica/blocco.
                if (System.currentTimeMillis() - prev.timestamp < 60 * 60_000L) return Result.success(prev)
            }
            val now = api.vehicleData(p.vin)
            analyze(ctx, p, db, api, prev, now)
            save(ctx, p, now)
            p.lastError = ""
            Result.success(now)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            p.lastError = msg
            if (e is ApiException && e.code == 408) {
                val s = (prev ?: Snapshot()).copy(timestamp = System.currentTimeMillis(), online = false, connectionState = "asleep")
                save(ctx, p, s)
                return Result.success(s)
            }
            Result.failure(e)
        }
    }

    /**
     * Modalità Home Assistant: lo stato arriva dal componente aggiuntivo (telemetria in tempo reale).
     * Gli avvisi Sentinella li invia Home Assistant: qui si importano solo nello storico.
     * @param fresh se true chiede all'add-on una lettura Fleet API (solo se l'auto è già sveglia).
     */
    private suspend fun runBackend(ctx: Context, p: Prefs, fresh: Boolean): Result<Snapshot?> = try {
        val be = Backend(p)
        val st = if (fresh) runCatching { be.refresh() }.getOrElse { be.status() } else be.status()
        if (st.vin.isNotBlank() && st.vin != p.vin) p.vin = st.vin
        if (st.name.isNotBlank()) p.vehicleName = st.name
        val s = Backend.toSnapshot(st)
        save(ctx, p, s)
        runCatching { syncBackendEvents(ctx, p, be) }
        p.lastError = ""
        Result.success(s)
    } catch (e: Exception) {
        p.lastError = e.message ?: e.toString()
        Result.failure(e)
    }

    /** Importa nello storico locale gli eventi nuovi del componente aggiuntivo (Aware, Panic, comandi…). */
    suspend fun syncBackendEvents(ctx: Context, p: Prefs = Prefs.get(ctx), be: Backend = Backend(p)): Int {
        var after = p.backendLastEventId
        var list = be.events(after)
        if (list.isEmpty() && after > 0) {
            // Registro dell'add-on azzerato (reinstallazione): riparti da capo.
            val all = be.events(0, 1)
            if (all.isNotEmpty() && all.maxOf { it.id } < after) { after = 0; list = be.events(0) }
        }
        if (list.isEmpty()) return 0
        val dao = AppDatabase.get(ctx).events()
        list.sortedBy { it.id }.forEach { e ->
            dao.insert(EventEntity(timestamp = e.ts, type = e.type, title = e.title, message = e.message))
        }
        p.backendLastEventId = list.maxOf { it.id }
        return list.size
    }

    private suspend fun save(ctx: Context, p: Prefs, s: Snapshot) {
        p.lastSnapshotJson = json.encodeToString(Snapshot.serializer(), s)
        runCatching { VigiloWidget.refresh(ctx) }
    }

    private suspend fun log(ctx: Context, type: String, title: String, msg: String, s: Snapshot?, channel: String?) {
        AppDatabase.get(ctx).events().insert(EventEntity(type = type, title = title, message = msg, latitude = s?.latitude, longitude = s?.longitude))
        if (channel != null) Notifier.show(ctx, channel, title, msg)
    }

    private suspend fun analyze(ctx: Context, p: Prefs, db: AppDatabase, api: TeslaApi, prev: Snapshot?, now: Snapshot) {
        val nowMs = System.currentTimeMillis()
        val nobody = now.userPresent != true

        // 1) Eventi sospetti con Sentinella attiva (dedotti)
        if (prev != null && prev.sentry == true && nobody) {
            val reasons = mutableListOf<String>()
            if (prev.doorsOpen == false && now.doorsOpen == true) reasons += "porta aperta"
            if (prev.trunkOpen == false && now.trunkOpen == true) reasons += "bagagliaio aperto"
            if (prev.frunkOpen == false && now.frunkOpen == true) reasons += "frunk aperto"
            if (prev.locked == true && now.locked == false) reasons += "auto sbloccata"
            if (prev.windowsOpen == false && now.windowsOpen == true) reasons += "finestrino aperto"
            movedConfirmed(p, prev, now)?.let { reasons += "auto spostata di $it m" }
            if (now.sentry == false && nowMs > p.expectedSentryChangeUntil) reasons += "Sentinella disattivata"
            if (reasons.isNotEmpty()) {
                log(ctx, EventType.SENTRY_ALERT, "⚠️ Evento con Sentinella attiva",
                    "Rilevato: ${reasons.joinToString(", ")}.", now, Notifier.CH_ALARM)
                react(ctx, p, api, now)
            }
        }
        if (prev?.sentry != true) p.moveCandidateCount = 0
        // Cambio Sentinella normale
        if (prev != null && prev.sentry != null && now.sentry != null && prev.sentry != now.sentry) {
            log(ctx, EventType.SENTRY_CHANGE, if (now.sentry) "Sentinella attivata" else "Sentinella disattivata", "Stato letto dall'auto", now, null)
        }

        // 2) Auto lasciata aperta
        if (now.locked == false && nobody && now.parked) {
            if (p.unlockedSince == 0L) p.unlockedSince = nowMs
            val mins = (nowMs - p.unlockedSince) / 60_000
            if (p.alertUnlocked && !p.unlockedNotified && mins >= p.unlockedMinutes) {
                log(ctx, EventType.UNLOCKED, "🔓 Auto aperta", "L'auto è sbloccata da almeno $mins minuti e nessuno è a bordo.", now, Notifier.CH_STATUS)
                p.unlockedNotified = true
            }
        } else if (now.locked == true) { p.unlockedSince = 0; p.unlockedNotified = false }

        // 3) Finestrini aperti
        if (now.windowsOpen == true && nobody && now.parked) {
            if (p.alertWindows && !p.windowsNotified) {
                log(ctx, EventType.WINDOW, "🪟 Finestrino aperto", "Uno o più finestrini risultano aperti con l'auto parcheggiata.", now, Notifier.CH_STATUS)
                p.windowsNotified = true
            }
        } else if (now.windowsOpen == false) p.windowsNotified = false

        // 4) Ricarica completata
        if (p.alertChargeComplete && prev != null && prev.chargingState != "Complete" && now.chargingState == "Complete") {
            log(ctx, EventType.CHARGE, "🔋 Ricarica completata", "Batteria al ${now.batteryLevel ?: "?"}% (${now.rangeKm?.toInt() ?: "?"} km).", now, Notifier.CH_STATUS)
        }

        // 5) Batteria bassa (con isteresi di 3 punti)
        val lvl = now.batteryLevel
        if (lvl != null) {
            if (p.alertLowBattery && !p.lowNotified && lvl <= p.lowBatteryPct && now.chargingState != "Charging") {
                log(ctx, EventType.LOW_BATTERY, "🪫 Batteria bassa", "Batteria al $lvl% (${now.rangeKm?.toInt() ?: "?"} km di autonomia).", now, Notifier.CH_STATUS)
                p.lowNotified = true
            } else if (lvl > p.lowBatteryPct + 3) p.lowNotified = false
        }
    }

    /**
     * Spostamento con conferma: un singolo salto del GPS (tipico in garage o tra palazzi) non basta.
     * Serve una seconda lettura che confermi la nuova posizione (entro un terzo della soglia) e lontana
     * dalla precedente. La posizione "di riferimento" resta quella dell'ultima lettura non sospetta.
     * Restituisce i metri se lo spostamento è confermato, altrimenti null.
     */
    internal fun movedConfirmed(p: Prefs, prev: Snapshot, now: Snapshot): Int? {
        val pl = prev.latitude; val po = prev.longitude; val nl = now.latitude; val no = now.longitude
        if (pl == null || po == null || nl == null || no == null || !now.parked) { p.moveCandidateCount = 0; return null }
        val threshold = p.movementMeters.toFloat()
        if (p.moveCandidateCount == 0) {
            if (distance(pl, po, nl, no) <= threshold) return null
            // Prima lettura lontana: la teniamo come candidata, con la posizione precedente come riferimento.
            p.moveAnchorLat = pl; p.moveAnchorLon = po
            p.moveCandidateLat = nl; p.moveCandidateLon = no; p.moveCandidateCount = 1
            return null
        }
        val fromAnchor = distance(p.moveAnchorLat, p.moveAnchorLon, nl, no)
        val fromCandidate = distance(p.moveCandidateLat, p.moveCandidateLon, nl, no)
        return when {
            // Seconda lettura coerente con la candidata e ancora lontana dal riferimento: spostamento vero.
            fromAnchor > threshold && fromCandidate <= maxOf(threshold / 3f, 15f) -> { p.moveCandidateCount = 0; fromAnchor.toInt() }
            // Tornata vicino al riferimento: era un salto del GPS.
            fromAnchor <= threshold -> { p.moveCandidateCount = 0; null }
            // Ancora lontana ma in un punto diverso: nuova candidata, stesso riferimento.
            else -> { p.moveCandidateLat = nl; p.moveCandidateLon = no; null }
        }
    }

    private fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val d = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, d)
        return d[0]
    }

    /** Reazione automatica: lampeggio fari e/o clacson (richiede comandi firmati via proxy sulla Model 3). */
    private suspend fun react(ctx: Context, p: Prefs, api: TeslaApi, s: Snapshot) {
        if (!s.parked) return
        if (p.autoFlash) runCatching { api.flash(p.vin) }.onSuccess {
            log(ctx, EventType.COMMAND, "Fari lampeggiati", "Reazione automatica all'evento", s, null)
        }.onFailure { log(ctx, EventType.ERROR, "Lampeggio non riuscito", it.message ?: "", s, Notifier.CH_STATUS) }
        if (p.autoHonk) runCatching { api.honk(p.vin) }.onSuccess {
            log(ctx, EventType.COMMAND, "Clacson suonato", "Reazione automatica all'evento", s, null)
        }.onFailure { log(ctx, EventType.ERROR, "Clacson non riuscito", it.message ?: "", s, Notifier.CH_STATUS) }
    }

    /** Comandi manuali dall'interfaccia: sveglia e retry sono gestiti da TeslaApi.command. */
    suspend fun manualCommand(
        ctx: Context,
        label: String,
        onProgress: ((String) -> Unit)? = null,
        backendCommand: String? = null,
        block: suspend (TeslaApi, String) -> String,
    ): Result<String> {
        val p = Prefs.get(ctx)
        if (p.backendMode) {
            // L'add-on registra già il comando nel suo storico: lo importiamo al prossimo aggiornamento.
            return runCatching {
                requireNotNull(backendCommand) { "Comando non disponibile con Home Assistant" }
                onProgress?.invoke("Invio tramite Home Assistant…")
                Backend(p).command(backendCommand)
            }
        }
        val api = TeslaApi(p).also { it.onProgress = onProgress }
        return runCatching {
            block(api, p.vin)
        }.onSuccess {
            log(ctx, EventType.COMMAND, label, it, lastSnapshot(p), null)
        }.onFailure {
            log(ctx, EventType.ERROR, "$label non riuscito", it.message ?: "", lastSnapshot(p), null)
        }
    }
}
