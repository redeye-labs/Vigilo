package io.github.redeyelabs.vigilo.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FleetTelemetrySupportTest {

    @Test
    fun configJwsBodyHasVinAndTrimmedToken() {
        val body = FleetTelemetrySupport.configJwsBody("XP7YTESTVIN00001", "  aaa.bbb.ccc\n")
        assertEquals("""{"vins":["XP7YTESTVIN00001"],"token":"aaa.bbb.ccc"}""", body.toString())
    }

    @Test
    fun defaultJwsUrlUsesProxyHostOnPort80() {
        assertEquals(
            "http://100.64.0.10/local/vigilo/signed-config.jws",
            FleetTelemetrySupport.defaultJwsUrl("https://100.64.0.10:4429"),
        )
        assertEquals(
            "http://homeassistant.tailnet-example.ts.net/local/vigilo/signed-config.jws",
            FleetTelemetrySupport.defaultJwsUrl("https://homeassistant.tailnet-example.ts.net:4429"),
        )
        assertEquals("", FleetTelemetrySupport.defaultJwsUrl("  "))
    }

    @Test
    fun decodeExpFromJwsPayloadAndFormatInRome() {
        val payload = b64Url("""{"hostname":"example.ts.net","port":8443,"exp":1826274221}""")
        val jws = "${b64Url("""{"alg":"ES256"}""")}." + payload + ".c2ln"
        assertEquals(1826274221L, FleetTelemetrySupport.decodeExpEpochSeconds(jws))
        assertEquals(
            "15 novembre 2027 alle 11:23",
            FleetTelemetrySupport.formatExp(1826274221L),
        )
    }

    @Test
    fun skipReasonsAndHttpErrorsAreInItalian() {
        assertEquals("chiave virtuale non abbinata", FleetTelemetrySupport.describeSkipReason("missing_key"))
        assertEquals("aggiorna il software dell'auto", FleetTelemetrySupport.describeSkipReason("unsupported_firmware"))
        assertEquals("fai di nuovo l'accesso in Impostazioni", FleetTelemetrySupport.describeHttpError(401))
        assertEquals("fai di nuovo l'accesso in Impostazioni", FleetTelemetrySupport.describeHttpError(403))
    }

    @Test
    fun autoRenewDecision() {
        assertTrue(FleetTelemetrySupport.shouldAutoRenew(jwsChanged = true, synced = true, configMissing = false, lastSkipPermanent = false))
        // "Non sincronizzata" da sola non basta: niente reinvii (e niente sveglie) inutili.
        assertFalse(FleetTelemetrySupport.shouldAutoRenew(jwsChanged = false, synced = false, configMissing = false, lastSkipPermanent = false))
        assertTrue(FleetTelemetrySupport.shouldAutoRenew(jwsChanged = false, synced = true, configMissing = false, lastSkipPermanent = false, expiresSoon = true))
        assertTrue(FleetTelemetrySupport.shouldAutoRenew(jwsChanged = false, synced = null, configMissing = true, lastSkipPermanent = false))
        assertFalse(FleetTelemetrySupport.shouldAutoRenew(jwsChanged = false, synced = false, configMissing = true, lastSkipPermanent = true))
        assertFalse(FleetTelemetrySupport.shouldAutoRenew(jwsChanged = false, synced = true, configMissing = false, lastSkipPermanent = false))
        assertTrue(FleetTelemetrySupport.shouldRunRenewNow(charging = true, wifiOrUnmetered = false))
        assertTrue(FleetTelemetrySupport.shouldRunRenewNow(charging = false, wifiOrUnmetered = true))
        assertFalse(FleetTelemetrySupport.shouldRunRenewNow(charging = false, wifiOrUnmetered = false))
    }

    @Test
    fun submitAndSyncMessages() {
        val skipped = mapOf("missing_key" to listOf("XP7YTESTVIN00001"))
        assertEquals(
            "Non applicata: chiave virtuale non abbinata.",
            FleetTelemetrySupport.formatSubmitResult(0, skipped),
        )
        assertEquals(
            "Configurazione inviata a 1 veicolo.",
            FleetTelemetrySupport.formatSubmitResult(1, emptyMap()),
        )
        assertEquals(
            "Sincronizzata: sì. Server: homeassistant.tailnet-example.ts.net:8443.",
            FleetTelemetrySupport.formatSyncStatus(true, "homeassistant.tailnet-example.ts.net", 8443, false),
        )
        assertEquals(
            "Sincronizzata: no. L'auto non ha ancora ricevuto la configurazione. Server: example:8443.",
            FleetTelemetrySupport.formatSyncStatus(false, "example", 8443, false),
        )
    }

    private fun b64Url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
}
