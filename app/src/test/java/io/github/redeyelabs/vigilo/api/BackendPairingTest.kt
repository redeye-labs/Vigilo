package io.github.redeyelabs.vigilo.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPairingTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd"

    @Test
    fun parsesPairLink() {
        val p = Backend.parsePairing("vigilo", "pair", "https://vigilo.tailnet-example.ts.net/", token)
        assertEquals("https://vigilo.tailnet-example.ts.net", p?.url)
        assertEquals(token, p?.token)
    }

    @Test
    fun rejectsHttpAndShortTokens() {
        assertNull(Backend.parsePairing("vigilo", "pair", "http://vigilo.example", token))
        assertNull(Backend.parsePairing("vigilo", "pair", "https://vigilo.example", "short"))
        assertNull(Backend.parsePairing("vigilo", "oauth", "https://vigilo.example", token))
    }

    @Test
    fun mapsStatusToSnapshot() {
        val st = Backend.Status(
            ok = true, vin = "5YJ3E7EA1KF000000", name = "Model 3",
            vehicle = Backend.VehicleState(sentry = "Aware", locked = true, battery = 71.6, gear = "P", telemetryConnected = true, updatedAt = 1000),
        )
        val s = Backend.toSnapshot(st)
        assertTrue(s.sentry == true)
        assertEquals("Aware", s.sentryMode)
        assertEquals(71, s.batteryLevel)
        assertTrue(s.online)
        assertTrue(s.parked)
        assertEquals(1000L, s.timestamp)
        val off = Backend.toSnapshot(st.copy(vehicle = st.vehicle.copy(sentry = "Off", telemetryConnected = false, connectionState = "asleep")))
        assertFalse(off.sentry!!)
        assertEquals("asleep", off.connectionState)
    }
}
