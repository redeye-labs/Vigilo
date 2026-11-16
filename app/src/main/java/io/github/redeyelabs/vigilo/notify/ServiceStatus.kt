package io.github.redeyelabs.vigilo.notify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import io.github.redeyelabs.vigilo.api.Snapshot
import kotlin.math.roundToInt

/** Testo e icona della notifica fissa di sorveglianza: batteria, autonomia e Sentinella. */
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

    /**
     * Icona piccola della barra di stato: percentuale in bianco su trasparente,
     * usata come maschera (Android la colora da solo). "100" viene rimpicciolito per entrare.
     */
    fun batteryIcon(percent: Int, sizePx: Int = 96): Bitmap {
        val text = percent.coerceIn(0, 100).toString()
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val inset = if (text.length >= 3) sizePx * 0.06f else sizePx * 0.04f
        val maxW = sizePx - 2 * inset
        val maxH = sizePx - 2 * inset
        var lo = 1f
        var hi = sizePx * 1.2f
        val bounds = Rect()
        repeat(24) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            paint.getTextBounds(text, 0, text.length, bounds)
            val h = paint.fontMetrics.descent - paint.fontMetrics.ascent
            if (bounds.width() <= maxW && h <= maxH) lo = mid else hi = mid
        }
        paint.textSize = lo
        paint.getTextBounds(text, 0, text.length, bounds)
        val x = sizePx / 2f
        val y = sizePx / 2f - (bounds.top + bounds.bottom) / 2f
        c.drawText(text, x, y, paint)
        return bmp
    }
}
