package io.github.redeyelabs.vigilo.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Riproduce il suono di Vigilo sul flusso SVEGLIA per qualche secondo, anche con il telefono in
 * silenzioso o vibrazione. Si usa con «Suona anche in silenzioso»: non dipende dal suono del canale,
 * che su alcuni telefoni (es. Samsung) può risultare vuoto o essere sostituito.
 */
object AlarmPlayer {
    private const val TAG = "VigiloAlarm"
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private val stopTask = Runnable { stop() }

    fun play(ctx: Context, kind: AlertKind) {
        val duration = SoundPlan.loudDurationMs(kind)
        if (duration <= 0) return
        val app = ctx.applicationContext
        main.post {
            stop()
            runCatching {
                val uri = Uri.parse("android.resource://${app.packageName}/${SoundPlan.rawRes(kind)}")
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(app, uri)
                mp.isLooping = true
                mp.setOnErrorListener { _, what, extra -> Log.w(TAG, "errore $what/$extra"); stop(); true }
                mp.prepare()
                mp.start()
                player = mp
                wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vigilo:alarm")
                    .apply { acquire(duration + 2_000L) }
                main.postDelayed(stopTask, duration)
            }.onFailure { Log.w(TAG, "riproduzione non riuscita", it); stop() }
        }
    }

    fun stop() {
        main.removeCallbacks(stopTask)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        wake?.let { if (it.isHeld) runCatching { it.release() } }
        wake = null
    }
}
