package io.github.redeyelabs.vigilo.work

import io.github.redeyelabs.vigilo.data.EventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorNotifyTest {
    private val now = 2_000_000_000_000L

    @Test
    fun notifiesFreshAwareAfterLastId() {
        assertTrue(
            Monitor.shouldNotifyBackendAlarm(
                notifyEnabled = true,
                type = EventType.SENTRY_AWARE,
                eventTs = now - 30_000L,
                eventId = 12,
                afterId = 10,
                now = now,
            ),
        )
        assertTrue(
            Monitor.shouldNotifyBackendAlarm(
                notifyEnabled = true,
                type = EventType.SENTRY_PANIC,
                eventTs = now - 9 * 60_000L,
                eventId = 13,
                afterId = 10,
                now = now,
            ),
        )
    }

    @Test
    fun skipsWhenDisabledOrNotAlarmOrOldOrAlreadySeen() {
        assertFalse(
            Monitor.shouldNotifyBackendAlarm(
                notifyEnabled = false,
                type = EventType.SENTRY_AWARE,
                eventTs = now - 1_000L,
                eventId = 12,
                afterId = 10,
                now = now,
            ),
        )
        assertFalse(
            Monitor.shouldNotifyBackendAlarm(
                notifyEnabled = true,
                type = EventType.COMMAND,
                eventTs = now - 1_000L,
                eventId = 12,
                afterId = 10,
                now = now,
            ),
        )
        assertFalse(
            Monitor.shouldNotifyBackendAlarm(
                notifyEnabled = true,
                type = EventType.SENTRY_AWARE,
                eventTs = now - 11 * 60_000L,
                eventId = 12,
                afterId = 10,
                now = now,
            ),
        )
        assertFalse(
            Monitor.shouldNotifyBackendAlarm(
                notifyEnabled = true,
                type = EventType.SENTRY_PANIC,
                eventTs = now - 1_000L,
                eventId = 10,
                afterId = 10,
                now = now,
            ),
        )
        assertFalse(
            Monitor.shouldNotifyBackendAlarm(
                notifyEnabled = true,
                type = EventType.SENTRY_AWARE,
                eventTs = 0L,
                eventId = 12,
                afterId = 10,
                now = now,
            ),
        )
    }
}
