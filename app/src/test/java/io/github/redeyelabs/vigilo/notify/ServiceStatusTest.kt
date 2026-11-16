package io.github.redeyelabs.vigilo.notify

import io.github.redeyelabs.vigilo.api.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceStatusTest {
    @Test
    fun formatsBatteryRangeAndSentry() {
        val s = Snapshot(batteryLevel = 78, rangeKm = 412.4, sentry = true, sentryMode = "Armed")
        assertEquals("🔋 78% · 412 km · Sentinella attiva", ServiceStatus.text(s))
    }

    @Test
    fun usesAwareAndPanicLabels() {
        assertEquals(
            "🔋 50% · Sentinella in allerta",
            ServiceStatus.text(Snapshot(batteryLevel = 50, sentryMode = "Aware")),
        )
        assertEquals(
            "Sentinella: allarme",
            ServiceStatus.text(Snapshot(sentryMode = "Panic")),
        )
        assertEquals(
            "412 km · Sentinella spenta",
            ServiceStatus.text(Snapshot(rangeKm = 411.6, sentry = false, sentryMode = "Off")),
        )
    }

    @Test
    fun returnsNullWhenNothingToShow() {
        assertNull(ServiceStatus.text(null))
        assertNull(ServiceStatus.text(Snapshot()))
    }

    @Test
    fun fallbackHasNoHomeAssistantWording() {
        assertFalse(ServiceStatus.FALLBACK_TEXT.contains("Home Assistant", ignoreCase = true))
        assertFalse(ServiceStatus.FALLBACK_TEXT.contains("In ascolto", ignoreCase = true))
        val text = ServiceStatus.text(Snapshot(batteryLevel = 78, rangeKm = 412.0, sentryMode = "Armed"))!!
        assertFalse(text.contains("Home Assistant", ignoreCase = true))
        assertFalse(text.contains("In ascolto", ignoreCase = true))
    }
}
