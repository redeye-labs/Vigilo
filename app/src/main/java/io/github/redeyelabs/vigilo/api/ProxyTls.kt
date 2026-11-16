package io.github.redeyelabs.vigilo.api

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.BCSSLSocket
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider
import java.net.InetAddress
import java.net.Socket
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS per il solo tesla-http-proxy.
 *
 * L'add-on Home Assistant genera un certificato autofirmato EC secp521r1. Conscrypt/Android
 * nel ClientHello non offre `ecdsa_secp521r1_sha512`.
 *
 * Su Android esiste già un provider registrato con il nome "BC" (versione ridotta in
 * `com.android.org.bouncycastle`) che NON ha SHA-512. Non si deve mai risolvere il provider
 * per nome ("BC" / "BCJSSE"): si usano istanze nostre, passate a JcaTlsCryptoProvider e a
 * SSLContext.getInstance("TLS", providerInstance). Non si chiama Security.addProvider.
 * Il client Tesla Fleet API resta su OkHttp standard.
 */
internal object ProxyTls {

    private val namedGroups = arrayOf(
        "x25519", "secp256r1", "secp384r1", "secp521r1", "x448", "ffdhe2048", "ffdhe3072",
    )

    private val signatureSchemes = arrayOf(
        "ecdsa_secp256r1_sha256",
        "ecdsa_secp384r1_sha384",
        "ecdsa_secp521r1_sha512",
        "rsa_pss_rsae_sha256",
        "rsa_pss_rsae_sha384",
        "rsa_pss_rsae_sha512",
        "rsa_pss_pss_sha256",
        "rsa_pss_pss_sha384",
        "rsa_pss_pss_sha512",
        "rsa_pkcs1_sha256",
        "rsa_pkcs1_sha384",
        "rsa_pkcs1_sha512",
        "ed25519",
        "ed448",
        "ecdsa_sha1",
        "rsa_pkcs1_sha1",
    )

    /** Istanza del provider JCE completo, mai registrata in Security (evita il conflitto col "BC" di Android). */
    private val bcJce: Provider by lazy { BouncyCastleProvider() }

    /** Istanza JSSE agganciata all'istanza JCE, non al nome "BC". */
    private val bcJsse: Provider by lazy {
        val crypto = JcaTlsCryptoProvider().setProvider(bcJce)
        BouncyCastleJsseProvider(false, crypto)
    }

    fun socketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS", bcJsse)
        ctx.init(null, arrayOf(trustManager), null)
        return ConfiguredFactory(ctx.socketFactory)
    }

    private class ConfiguredFactory(
        private val delegate: SSLSocketFactory,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = configure(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): Socket =
            configure(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            configure(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(address, port, localAddress, localPort))
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            configure(delegate.createSocket(s, host, port, autoClose))

        private fun configure(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supported = socket.supportedProtocols.toSet()
                val protocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
                if (protocols.isNotEmpty()) socket.enabledProtocols = protocols
                if (socket is BCSSLSocket) {
                    val params = socket.parameters
                    params.namedGroups = namedGroups
                    params.signatureSchemes = signatureSchemes
                    socket.parameters = params
                }
            }
            return socket
        }
    }
}
