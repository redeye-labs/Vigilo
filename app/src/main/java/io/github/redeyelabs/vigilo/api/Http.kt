package io.github.redeyelabs.vigilo.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Http {
    /**
     * Client Tesla Fleet API: TLS della piattaforma (Conscrypt su Android, SunJSSE nei test JVM).
     * Si forza esplicitamente, altrimenti OkHttp, vedendo bctls nel classpath, cercherebbe
     * il provider "BCJSSE" per nome (e su Android il "BC" di sistema è incompleto).
     */
    private val platformTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val client: OkHttpClient = run {
        val tm = platformTrustManager
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .sslSocketFactory(ctx.socketFactory, tm)
            .build()
    }

    /** Client con attesa lunga (comandi che devono svegliare l'auto, companion lenti). */
    val slowClient: OkHttpClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()

    private var pinnedFor: String? = null
    private var pinnedClient: OkHttpClient? = null

    /** Normalizza l'indirizzo del proxy: aggiunge https:// e la porta 4429 se mancano. */
    fun normalizeProxyUrl(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isBlank()) return ""
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://")
        val after = s.substringAfter("://")
        val hostAndPort = after.substringBefore('/')
        val path = after.removePrefix(hostAndPort)
        val hasPort = if (hostAndPort.startsWith("[")) hostAndPort.contains("]:")
        else hostAndPort.contains(':')
        val hostWithPort = if (hasPort) hostAndPort else "$hostAndPort:4429"
        return "$scheme://$hostWithPort$path".trimEnd('/')
    }

    fun normalizeFingerprint(sha256Hex: String): String =
        sha256Hex.replace(":", "").replace(" ", "").lowercase()

    /** Forma breve per mostrarla all'utente, es. A1B2C3D4…E5F60708. */
    fun shortFingerprint(sha256Hex: String): String {
        val fp = normalizeFingerprint(sha256Hex)
        if (fp.length < 16) return fp.uppercase()
        return fp.take(8).uppercase() + "…" + fp.takeLast(8).uppercase()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun invalidatePinnedClient() {
        pinnedFor = null
        pinnedClient = null
    }

    /**
     * Client per il tesla-http-proxy, con TLS Bouncy Castle JSSE (necessario per i certificati
     * autofirmati secp521r1 dell'add-on Home Assistant). Se è indicata un'impronta SHA-256,
     * accetta SOLO il certificato con quell'impronta. Senza impronta usa la validazione standard
     * sul client Tesla normale, non questo.
     */
    @Synchronized
    fun proxyClient(sha256Hex: String): OkHttpClient {
        val fp = normalizeFingerprint(sha256Hex)
        if (fp.isBlank()) return client
        if (pinnedFor == fp && pinnedClient != null) return pinnedClient!!
        val tm = pinningTrustManager(fp)
        return client.newBuilder()
            .sslSocketFactory(ProxyTls.socketFactory(tm), tm)
            .hostnameVerifier { _, _ -> true } // l'identità è garantita dall'impronta del certificato
            .build()
            .also { pinnedFor = fp; pinnedClient = it }
    }

    /**
     * Si collega al proxy con un trust manager che accetta il certificato e ne legge l'impronta
     * SHA-256 della foglia (trust on first use).
     */
    fun captureLeafSha256(baseUrl: String): String {
        var captured: String? = null
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("non usato")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("nessun certificato")
                captured = sha256Hex(leaf.encoded)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val probe = client.newBuilder()
            .sslSocketFactory(ProxyTls.socketFactory(tm), tm)
            .hostnameVerifier { _, _ -> true }
            .build()
        try {
            probeOnce(probe, baseUrl)
        } catch (e: Exception) {
            // Se l'handshake TLS è riuscito abbiamo già l'impronta, anche se l'HTTP poi fallisce.
            if (captured == null) throw e
        }
        return captured ?: throw IOException("Impossibile leggere il certificato del proxy")
    }

    /** Apre una connessione TLS/HTTP verso [baseUrl]; ignora il codice HTTP, propaga errori di rete/TLS. */
    fun probeOnce(httpClient: OkHttpClient, baseUrl: String, path: String = "/") {
        val url = baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { }
    }

    fun isCertificateMismatch(error: Throwable): Boolean {
        var e: Throwable? = error
        while (e != null) {
            if (e is CertificateException && (e.message ?: "").contains("Impronta", ignoreCase = true)) return true
            if ((e.message ?: "").contains("Impronta certificato proxy", ignoreCase = true)) return true
            e = e.cause
        }
        return false
    }

    fun isUnreachable(error: Throwable): Boolean {
        var e: Throwable? = error
        while (e != null) {
            if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) return true
            val msg = (e.message ?: "").lowercase()
            if (msg.contains("timeout") || msg.contains("failed to connect") ||
                msg.contains("unable to resolve") || msg.contains("connection refused") ||
                msg.contains("network is unreachable") || msg.contains("no address associated")
            ) return true
            e = e.cause
        }
        return false
    }

    fun unreachableHint(error: Throwable): String {
        val detail = error.message ?: error.javaClass.simpleName
        if (isCertificateMismatch(error)) {
            return "Il certificato del proxy è diverso da quello memorizzato. " +
                "Se hai reinstallato l'add-on, usa «Reimposta certificato» e collega di nuovo."
        }
        val base = "Impossibile raggiungere il proxy: $detail"
        return if (isUnreachable(error) || error is IOException || error is SSLHandshakeException) {
            "$base. Controlla che Tailscale sia attivo sul telefono e che l'app Tesla HTTP Proxy sia avviata."
        } else base
    }

    private fun pinningTrustManager(fp: String) = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("non usato")
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("nessun certificato")
            val d = sha256Hex(leaf.encoded)
            if (d != fp) throw CertificateException("Impronta certificato proxy diversa da quella impostata")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
