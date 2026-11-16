package io.github.redeyelabs.vigilo.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Simula la condizione Android: un provider di nome "BC" è già registrato ma non offre SHA-512.
 * L'handshake deve comunque riuscire, perché il client usa un'istanza JCE/JSSE e non il nome "BC".
 */
class P521ProxyTlsTest {

    @Before
    fun installAndroidLikeStrippedBc() {
        Security.removeProvider("BC")
        Security.removeProvider("BCJSSE")
        Security.insertProviderAt(DummyStrippedBc(), 1)
        val sha512ViaName = runCatching { MessageDigest.getInstance("SHA-512", "BC") }
        assertTrue(
            "Il provider fittizio 'BC' non deve offrire SHA-512 (come su Android)",
            sha512ViaName.isFailure,
        )
    }

    @Test
    fun handshakeSucceedsWithAddonStyleSecp521r1CertDespiteStrippedNamedBc() {
        val openssl = findOpenssl()
        assumeTrue("openssl non trovato sul PATH", openssl != null)

        val dir = Files.createTempDirectory("vigilo-p521-").toFile()
        dir.deleteOnExit()
        val key = FileIn(dir, "key.pem")
        val cert = FileIn(dir, "cert.pem")

        val gen = ProcessBuilder(
            openssl, "req", "-x509", "-nodes", "-newkey", "ec",
            "-pkeyopt", "ec_paramgen_curve:secp521r1",
            "-pkeyopt", "ec_param_enc:named_curve",
            "-subj", "/CN=localhost",
            "-keyout", key, "-out", cert, "-sha256", "-days", "3650",
            "-addext", "extendedKeyUsage = serverAuth",
            "-addext", "keyUsage = digitalSignature, keyCertSign, keyAgreement",
        ).directory(dir).redirectErrorStream(true).start()
        val genOut = gen.inputStream.bufferedReader().readText()
        assertTrue("generazione certificato fallita: $genOut", gen.waitFor(30, TimeUnit.SECONDS) && gen.exitValue() == 0)
        assertTrue(java.io.File(cert).isFile)

        val port = freePort()
        val server = ProcessBuilder(
            openssl, "s_server", "-accept", port.toString(), "-naccept", "8",
            "-cert", cert, "-key", key, "-www",
        ).directory(dir).redirectErrorStream(true).start()
        try {
            waitUntilListening(port, server)
            val url = "https://127.0.0.1:$port"
            val fp = Http.captureLeafSha256(url)
            assertEquals(64, fp.length)
            Http.probeOnce(Http.proxyClient(fp), url)
        } finally {
            server.destroy()
            server.waitFor(5, TimeUnit.SECONDS)
            if (server.isAlive) server.destroyForcibly()
        }
    }

    private class DummyStrippedBc : Provider("BC", 1.0, "Android-like stripped BC stub (no SHA-512)")

    private fun FileIn(dir: java.io.File, name: String) = java.io.File(dir, name).absolutePath

    private fun findOpenssl(): String? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(java.io.File.pathSeparator)) {
            val f = java.io.File(dir, "openssl")
            if (f.canExecute()) return f.absolutePath
        }
        val fallback = java.io.File("/usr/bin/openssl")
        return if (fallback.canExecute()) fallback.absolutePath else null
    }

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun waitUntilListening(port: Int, server: Process) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (!server.isAlive) {
                val out = server.inputStream.bufferedReader().readText()
                throw AssertionError("openssl s_server è uscito subito: $out")
            }
            if (portIsListening(port)) return
            Thread.sleep(50)
        }
        throw AssertionError("openssl s_server non è in ascolto sulla porta $port")
    }

    private fun portIsListening(port: Int): Boolean {
        val hex = "%04X".format(port)
        return listOf("/proc/net/tcp", "/proc/net/tcp6").any { path ->
            val f = java.io.File(path)
            f.isFile && f.readLines().any { line ->
                val cols = line.trim().split(Regex("\\s+"))
                cols.size > 3 && cols[1].endsWith(":$hex") && cols[3] == "0A"
            }
        }
    }
}
