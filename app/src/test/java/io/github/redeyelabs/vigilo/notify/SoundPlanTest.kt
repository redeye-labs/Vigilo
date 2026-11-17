package io.github.redeyelabs.vigilo.notify

import io.github.redeyelabs.vigilo.data.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPlanTest {
    @Test
    fun eventTypesMapToContextualSounds() {
        assertEquals(AlertKind.PANIC, SoundPlan.kindForEvent(EventType.SENTRY_PANIC))
        assertEquals(AlertKind.AWARE, SoundPlan.kindForEvent(EventType.SENTRY_AWARE))
        assertEquals(AlertKind.AWARE, SoundPlan.kindForEvent(EventType.SENTRY_ALERT))
        assertEquals(AlertKind.INFO, SoundPlan.kindForEvent(EventType.LOW_BATTERY))
        assertEquals(AlertKind.INFO, SoundPlan.kindForEvent(EventType.ERROR))
    }

    @Test
    fun channelsAreSeparatePerKindAndNew() {
        assertEquals(Notifier.CH_PANIC, SoundPlan.channelFor(AlertKind.PANIC, false))
        assertEquals(Notifier.CH_AWARE, SoundPlan.channelFor(AlertKind.AWARE, false))
        assertEquals(Notifier.CH_INFO, SoundPlan.channelFor(AlertKind.INFO, false))
        assertEquals(Notifier.CH_INFO, SoundPlan.channelFor(AlertKind.INFO, true))
        assertEquals(Notifier.CH_ALARM_LOUD, SoundPlan.channelFor(AlertKind.PANIC, true))
        assertEquals(Notifier.CH_ALARM_LOUD, SoundPlan.channelFor(AlertKind.AWARE, true))
        // Id nuovi: i canali vecchi (suono di sistema) non vengono riusati.
        listOf(Notifier.CH_PANIC, Notifier.CH_AWARE, Notifier.CH_INFO, Notifier.CH_ALARM_LOUD).forEach {
            assertTrue(it !in listOf("vigilo_alarm", "vigilo_alarm_v2", "vigilo_alarm_loud", "vigilo_status"))
        }
    }

    @Test
    fun panicLoopsLongerThanAware() {
        assertTrue(SoundPlan.loudDurationMs(AlertKind.PANIC) >= 8_000)
        assertTrue(SoundPlan.loudDurationMs(AlertKind.AWARE) in 1_000..4_000)
        assertEquals(0L, SoundPlan.loudDurationMs(AlertKind.INFO))
        assertNotEquals(SoundPlan.rawRes(AlertKind.PANIC), SoundPlan.rawRes(AlertKind.AWARE))
    }
}
