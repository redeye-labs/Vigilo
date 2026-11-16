package io.github.redeyelabs.vigilo

import android.app.Application
import io.github.redeyelabs.vigilo.notify.Notifier
import io.github.redeyelabs.vigilo.work.Scheduler
import org.osmdroid.config.Configuration

class VigiloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfigCompat.version(this@VigiloApp)}"
            osmdroidBasePath = filesDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid-tiles")
        }
        Scheduler.applyPolling(this)
        Scheduler.applyTelemetryRenew(this)
    }
}

object BuildConfigCompat {
    fun version(app: Application): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "1"
}
