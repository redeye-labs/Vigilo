package io.github.redeyelabs.vigilo.work

import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun restartsAfterBootAndAppUpdate() {
        assertTrue(Intent.ACTION_BOOT_COMPLETED in BootReceiver.RESTART_ACTIONS)
        assertTrue(Intent.ACTION_MY_PACKAGE_REPLACED in BootReceiver.RESTART_ACTIONS)
        assertTrue("android.intent.action.QUICKBOOT_POWERON" in BootReceiver.RESTART_ACTIONS)
    }
}
