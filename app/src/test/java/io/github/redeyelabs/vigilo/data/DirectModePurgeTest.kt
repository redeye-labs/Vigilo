package io.github.redeyelabs.vigilo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectModePurgeTest {
    @Test
    fun purgesDirectModeSecretsButNotPairing() {
        listOf("client_secret", "client_id", "companion_key", "access_token", "refresh_token", "pkce_verifier", "proxy_sha256")
            .forEach { assertTrue(it, it in DIRECT_MODE_KEYS) }
        listOf("backend_url", "backend_token", "vin", "vehicle_name_custom", "notify_app_alarms")
            .forEach { assertFalse(it, it in DIRECT_MODE_KEYS) }
    }
}
