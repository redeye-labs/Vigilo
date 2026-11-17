package io.github.redeyelabs.vigilo.api

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Client HTTP condiviso per il server Vigilo (TLS della piattaforma; certificato pubblico di Tailscale Funnel). */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
}
