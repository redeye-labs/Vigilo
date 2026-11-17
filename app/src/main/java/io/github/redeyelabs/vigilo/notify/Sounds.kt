package io.github.redeyelabs.vigilo.notify

import io.github.redeyelabs.vigilo.R
import io.github.redeyelabs.vigilo.data.EventType

/** Tipo di avviso: sceglie suono e canale. */
enum class AlertKind { PANIC, AWARE, INFO }

/** Regole pure (senza Android) per suoni e canali: testabili. */
object SoundPlan {
    /** Panic = allarme vero; Aware ed eventi dedotti dal telefono = attenzione. */
    fun kindForEvent(type: String): AlertKind = when (type) {
        EventType.SENTRY_PANIC -> AlertKind.PANIC
        EventType.SENTRY_AWARE, EventType.SENTRY_ALERT -> AlertKind.AWARE
        else -> AlertKind.INFO
    }

    fun rawRes(kind: AlertKind): Int = when (kind) {
        AlertKind.PANIC -> R.raw.vigilo_panic
        AlertKind.AWARE -> R.raw.vigilo_aware
        AlertKind.INFO -> R.raw.vigilo_info
    }

    /**
     * Canale per un avviso. Con «Suona anche in silenzioso» Panic e Aware vanno sul canale sveglia,
     * che non ha suono proprio: il suono lo riproduce [AlarmPlayer] sul flusso sveglia.
     */
    fun channelFor(kind: AlertKind, bypassSilent: Boolean): String = when {
        kind == AlertKind.INFO -> Notifier.CH_INFO
        bypassSilent -> Notifier.CH_ALARM_LOUD
        kind == AlertKind.PANIC -> Notifier.CH_PANIC
        else -> Notifier.CH_AWARE
    }

    /** Durata della riproduzione esplicita sul flusso sveglia: il Panic si ripete per qualche secondo. */
    fun loudDurationMs(kind: AlertKind): Long = when (kind) {
        AlertKind.PANIC -> 12_000L
        AlertKind.AWARE -> 2_600L   // doppio bip ripetuto due volte
        AlertKind.INFO -> 0L
    }
}
