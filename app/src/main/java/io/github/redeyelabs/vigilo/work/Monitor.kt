package io.github.redeyelabs.vigilo.work

import android.content.Context
import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.api.Snapshot
import io.github.redeyelabs.vigilo.data.AppDatabase
import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.EventType
import io.github.redeyelabs.vigilo.data.Prefs
import io.github.redeyelabs.vigilo.notify.Notifier
import io.github.redeyelabs.vigilo.notify.SoundPlan
import io.github.redeyelabs.vigilo.widget.VigiloWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Lettura dello stato e degli eventi dal server Vigilo (componente aggiuntivo di Home Assistant o Docker).
 * Usata dal lavoro periodico (WorkManager), dal servizio in primo piano e dal pulsante "Aggiorna".
 */
object Monitor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    fun lastSnapshot(p: Prefs): Snapshot? =
        p.lastSnapshotJson.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<Snapshot>(it) }.getOrNull() }

    /** @param force mantenuto per compatibilità con i chiamanti; @param fresh chiede una lettura dall'auto. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun runOnce(ctx: Context, force: Boolean = false, fresh: Boolean = false): Result<Snapshot?> = mutex.withLock {
        val p = Prefs.get(ctx)
        if (!p.backendMode) return Result.failure(IllegalStateException("Collega l'app al server Vigilo (Guida › Abbina questo telefono)."))
        runBackend(ctx, p, fresh)
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
        runCatching { syncBackendEventsLocked(ctx, p, be) }
        p.lastError = ""
        Result.success(s)
    } catch (e: Exception) {
        p.lastError = e.message ?: e.toString()
        Result.failure(e)
    }

    /** Importa nello storico locale gli eventi nuovi del componente aggiuntivo (Aware, Panic, comandi…). */
    suspend fun syncBackendEvents(ctx: Context, p: Prefs = Prefs.get(ctx), be: Backend = Backend(p)): Int =
        mutex.withLock { syncBackendEventsLocked(ctx, p, be) }

    private suspend fun syncBackendEventsLocked(ctx: Context, p: Prefs, be: Backend): Int {
        var after = p.backendLastEventId
        var list = be.events(after)
        if (list.isEmpty() && after > 0) {
            // Registro dell'add-on azzerato (reinstallazione): riparti da capo.
            val all = be.events(0, 1)
            if (all.isNotEmpty() && all.maxOf { it.id } < after) { after = 0; list = be.events(0) }
        }
        if (list.isEmpty()) return 0
        val dao = AppDatabase.get(ctx).events()
        val notify = p.notifyAlarmsFromApp
        val now = System.currentTimeMillis()
        list.sortedBy { it.id }.forEach { e ->
            dao.insert(EventEntity(timestamp = e.ts, type = e.type, title = e.title, message = e.message))
            if (shouldNotifyBackendAlarm(notify, e.type, e.ts, e.id, after, now)) {
                Notifier.show(ctx, Notifier.CH_ALARM, e.title.ifBlank { "Allarme Sentinella" }, e.message, SoundPlan.kindForEvent(e.type))
            }
        }
        p.backendLastEventId = list.maxOf { it.id }
        return list.size
    }

    /**
     * Notifica solo allarmi Aware/Panic nuovi, e solo se l'evento non è più vecchio di [maxAgeMs].
     * Così, alla prima sincronizzazione dopo aver acceso l'opzione, gli eventi già visti
     * (id ≤ last id) e quelli vecchi non fanno suonare il telefono.
     */
    internal fun shouldNotifyBackendAlarm(
        notifyEnabled: Boolean,
        type: String,
        eventTs: Long,
        eventId: Long,
        afterId: Long,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 10 * 60_000L,
    ): Boolean {
        if (!notifyEnabled) return false
        if (!EventType.isAlert(type)) return false
        if (eventId <= afterId) return false
        if (eventTs <= 0L) return false
        if (now - eventTs > maxAgeMs) return false
        return true
    }

    private suspend fun save(ctx: Context, p: Prefs, s: Snapshot) {
        p.lastSnapshotJson = json.encodeToString(Snapshot.serializer(), s)
        runCatching { VigiloWidget.refresh(ctx) }
    }

    /** Comandi manuali dall'interfaccia, inviati tramite il server Vigilo (che li registra nel suo storico). */
    suspend fun manualCommand(
        ctx: Context,
        onProgress: ((String) -> Unit)? = null,
        backendCommand: String,
    ): Result<String> {
        val p = Prefs.get(ctx)
        return runCatching {
            check(p.backendMode) { "Collega prima l'app al server Vigilo." }
            onProgress?.invoke("Invio tramite il server Vigilo…")
            Backend(p).command(backendCommand)
        }
    }
}
