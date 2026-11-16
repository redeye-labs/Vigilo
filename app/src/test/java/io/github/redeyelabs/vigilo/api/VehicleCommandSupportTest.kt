package io.github.redeyelabs.vigilo.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCommandSupportTest {

    @Test
    fun sentryPayloadIsBooleanOnField() {
        assertEquals("""{"on":true}""", VehicleCommandSupport.sentryBody(true).toString())
        assertEquals("""{"on":false}""", VehicleCommandSupport.sentryBody(false).toString())
    }

    @Test
    fun classifiesProxyDeadlineAndUnavailableAsTransient() {
        val deadline = ApiException(500, """Errore Fleet API (500): {"response":null,"error":"context deadline exceeded"}""")
        val unavailable = ApiException(500, """{"response":null,"error":"vehicle unavailable"}""")
        val requestTimeout = ApiException(408, "Auto addormentata o non raggiungibile (408)")
        assertTrue(VehicleCommandSupport.isTransientUnreachable(deadline))
        assertTrue(VehicleCommandSupport.isTransientUnreachable(unavailable))
        assertTrue(VehicleCommandSupport.isTransientUnreachable(requestTimeout))
        assertFalse(VehicleCommandSupport.isTransientUnreachable(ApiException(401, "Token non valido o scaduto (401)")))
        assertFalse(VehicleCommandSupport.isTransientUnreachable(ApiException(200, "Comando set_sentry_mode rifiutato: already_on")))
    }

    @Test
    fun transientErrorsMapToFriendlyItalianMessage() {
        val raw = ApiException(500, """Errore Fleet API (500): {"response":null,"error":"context deadline exceeded"}""")
        assertEquals(VEHICLE_UNREACHABLE_MESSAGE, VehicleCommandSupport.userMessage(raw))
        assertEquals(
            VEHICLE_UNREACHABLE_MESSAGE,
            VehicleCommandSupport.userMessage(ApiException(0, VEHICLE_UNREACHABLE_MESSAGE)),
        )
        assertEquals("Token non valido o scaduto (401)", VehicleCommandSupport.userMessage(ApiException(401, "Token non valido o scaduto (401)")))
    }
}
