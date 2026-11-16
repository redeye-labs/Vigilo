package io.github.redeyelabs.vigilo.ui

fun formatUpdatedAgo(timestamp: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (timestamp == null) return "Non ancora aggiornato"
    val s = ((nowMs - timestamp) / 1000L).coerceAtLeast(0)
    return when {
        s < 5 -> "aggiornato ora"
        s < 60 -> "aggiornato $s s fa"
        s < 120 -> "aggiornato 1 min fa"
        s < 3600 -> "aggiornato ${s / 60} min fa"
        s < 7200 -> "aggiornato 1 h fa"
        else -> "aggiornato ${s / 3600} h fa"
    }
}
