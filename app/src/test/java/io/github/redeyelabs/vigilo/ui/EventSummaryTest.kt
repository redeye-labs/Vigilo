package io.github.redeyelabs.vigilo.ui

import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.EventType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EventSummaryTest {
    @Test
    fun countsAndBucketsLast24Hours() {
        val now = 2_000_000_000_000L
        val ev = listOf(
            EventEntity(timestamp = now - 30 * 60_000, type = EventType.SENTRY_ALERT, title = "a", message = ""),
            EventEntity(timestamp = now - 5 * 3600_000, type = EventType.UNLOCKED, title = "b", message = ""),
            EventEntity(timestamp = now - 30 * 3600_000, type = EventType.SENTRY_ALERT, title = "old", message = ""),
        )
        val last = eventsInLast24h(ev, now)
        assertEquals(2, last.size)
        assertEquals(1, sentryAlertCount(last))
        val buckets = hourlyEventBuckets(last, now)
        assertEquals(24, buckets.size)
        assertEquals(1, buckets[23]) // last 30 minutes = current hour
        assertEquals(1, buckets[19]) // 5 hours ago = 19 hours after window start
        assertEquals(2, buckets.sum())
    }
}
