package io.github.redeyelabs.vigilo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeAgoTest {
    @Test
    fun formatsRecentAndMinutes() {
        val now = 1_000_000L
        assertEquals("Non ancora aggiornato", formatUpdatedAgo(null, now))
        assertEquals("aggiornato ora", formatUpdatedAgo(now - 2_000, now))
        assertEquals("aggiornato 12 s fa", formatUpdatedAgo(now - 12_000, now))
        assertEquals("aggiornato 1 min fa", formatUpdatedAgo(now - 60_000, now))
        assertEquals("aggiornato 5 min fa", formatUpdatedAgo(now - 5 * 60_000, now))
        assertEquals("aggiornato 1 h fa", formatUpdatedAgo(now - 3_600_000, now))
    }
}
