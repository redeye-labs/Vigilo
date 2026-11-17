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

    @Test
    fun mapsClimateOdometerTrunkFromServerJson() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val st = json.decodeFromString(
            Backend.Status.serializer(),
            """{"ok":true,"vehicle":{"sentry":"Armed","inside_temp":21.5,"outside_temp":7.2,"odometer_km":16093.4,"trunk_open":true,"frunk_open":false,"nuovo":1}}""",
        )
        val s = Backend.toSnapshot(st)
        assertEquals(21.5, s.insideTemp!!, 0.0)
        assertEquals(7.2, s.outsideTemp!!, 0.0)
        assertEquals(16093.4, s.odometerKm!!, 0.0)
        assertTrue(s.trunkOpen!!)
        assertFalse(s.frunkOpen!!)
        // Server più vecchio (senza i nuovi campi): restano vuoti, nessun errore.
        val old = Backend.toSnapshot(json.decodeFromString(Backend.Status.serializer(), """{"ok":true,"vehicle":{"sentry":"Off"}}"""))
        assertNull(old.insideTemp)
        assertNull(old.odometerKm)
        assertNull(old.trunkOpen)
    }
}
