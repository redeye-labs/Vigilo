package io.github.redeyelabs.vigilo.notify

import io.github.redeyelabs.vigilo.api.Snapshot
import kotlin.math.roundToInt

/** Testo della notifica fissa di sorveglianza: batteria, autonomia e Sentinella. */
object ServiceStatus {
    const val FALLBACK_TEXT = "In attesa dello stato dell'auto"

    /** Es. "🔋 78% · 412 km · Sentinella attiva". Null se non c'è ancora nulla da mostrare. */
    fun text(s: Snapshot?): String? {
        if (s == null) return null
        val parts = mutableListOf<String>()
        s.batteryLevel?.let { parts += "🔋 $it%" }
        s.rangeKm?.let { parts += "${it.roundToInt()} km" }
        sentryLabel(s)?.let { parts += it }
        return parts.joinToString(" · ").ifBlank { null }
    }

    fun sentryLabel(s: Snapshot): String? = when (s.sentryMode?.trim()?.lowercase()) {
        "panic" -> "Sentinella: allarme"
        "aware" -> "Sentinella in allerta"
        "armed", "idle", "quiet", "on" -> "Sentinella attiva"
        "off" -> "Sentinella spenta"
        else -> when (s.sentry) {
            true -> "Sentinella attiva"
            false -> "Sentinella spenta"
            null -> null
        }
    }
}
