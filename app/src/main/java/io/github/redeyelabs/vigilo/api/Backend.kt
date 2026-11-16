package io.github.redeyelabs.vigilo.api

import android.net.Uri
import io.github.redeyelabs.vigilo.data.Prefs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client del componente aggiuntivo "Vigilo" per Home Assistant (API /api/v1).
 * L'add-on è raggiungibile in HTTPS tramite Tailscale Funnel; ogni richiesta porta il token di
 * abbinamento letto dal QR (Authorization: Bearer). Nessuna chiamata qui sveglia l'auto, tranne i comandi.
 */
class Backend(private val baseUrl: String, private val token: String) {

    constructor(p: Prefs) : this(p.backendUrl, p.backendToken)

    @Serializable
    data class VehicleState(
        val sentry: String = "",
        @SerialName("sentry_at") val sentryAt: Long = 0,
        val locked: Boolean? = null,
        @SerialName("doors_open") val doorsOpen: Boolean? = null,
        @SerialName("windows_open") val windowsOpen: Boolean? = null,
        val battery: Double? = null,
        @SerialName("range_km") val rangeKm: Double? = null,
        val charging: String = "",
        val lat: Double? = null,
        val lon: Double? = null,
        val gear: String = "",
        val online: Boolean = false,
        @SerialName("connection_state") val connectionState: String = "",
        @SerialName("telemetry_connected") val telemetryConnected: Boolean = false,
        @SerialName("telemetry_last_seen") val telemetryLastSeen: Long = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
    )

    @Serializable
    data class Status(
        val ok: Boolean = false,
        val version: String = "",
        val vin: String = "",
        val name: String = "",
        val vehicle: VehicleState = VehicleState(),
        @SerialName("telemetry_exp") val telemetryExp: Long = 0,
        @SerialName("key_paired") val keyPaired: Boolean? = null,
        val error: String? = null,
    )

    @Serializable
    data class Event(val id: Long, val ts: Long, val type: String, val title: String = "", val message: String = "")

    @Serializable
    private data class Events(val ok: Boolean = false, val events: List<Event> = emptyList())

    @Serializable
    data class Check(val id: String, val label: String, val ok: Boolean, val detail: String = "")

    @Serializable
    private data class Health(val ok: Boolean = false, val checks: List<Check> = emptyList())

    @Serializable
    data class Settings(
        @SerialName("notify_targets") val notifyTargets: List<String> = emptyList(),
        @SerialName("notify_aware") val notifyAware: Boolean = true,
        @SerialName("notify_panic") val notifyPanic: Boolean = true,
        @SerialName("aware_throttle_sec") val awareThrottleSec: Int = 120,
        @SerialName("notify_problems") val notifyProblems: Boolean = true,
    )

    @Serializable
    data class SettingsResponse(
        val ok: Boolean = false,
        val settings: Settings = Settings(),
        @SerialName("available_targets") val availableTargets: List<String> = emptyList(),
    )

    @Serializable
    private data class CommandResponse(val ok: Boolean = false, val message: String = "", val status: Status? = null)

    @Serializable
    private data class ErrorBody(val ok: Boolean = false, val error: String = "")

    fun status(): Status = get("/api/v1/status")
    /** Lettura dalla Fleet API solo se l'auto è già sveglia (l'add-on non la sveglia). */
    fun refresh(): Status = send("POST", "/api/v1/refresh", "{}".toRequestBody(JSON))
    fun events(after: Long, limit: Int = 200): List<Event> = get<Events>("/api/v1/events?after=$after&limit=$limit").events
    fun health(deep: Boolean): List<Check> = get<Health>("/api/v1/health" + if (deep) "?deep=1" else "").checks
    fun settings(): SettingsResponse = get("/api/v1/settings")
    fun saveSettings(s: Settings): SettingsResponse =
        send("PUT", "/api/v1/settings", json.encodeToString(Settings.serializer(), s).toRequestBody(JSON))

    /** Comandi: sentry_on, sentry_off, lock, unlock, honk, flash. Restituisce il messaggio dell'add-on. */
    fun command(name: String): String {
        val r: CommandResponse = send("POST", "/api/v1/command", """{"command":"$name"}""".toRequestBody(JSON), slow = true)
        return r.message.ifBlank { "Fatto" }
    }

    private inline fun <reified T> get(path: String): T = send("GET", path, null)

    private inline fun <reified T> send(method: String, path: String, body: RequestBody?, slow: Boolean = false): T {
        require(baseUrl.startsWith("https://")) { "Home Assistant non abbinato: scansiona il QR del componente aggiuntivo." }
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, body)
            .build()
        val client = if (slow) Http.client.newBuilder().readTimeout(110, TimeUnit.SECONDS).build() else Http.client
        val text: String
        val code: Int
        try {
            client.newCall(req).execute().use { resp ->
                code = resp.code
                text = resp.body?.string().orEmpty()
            }
        } catch (e: java.io.IOException) {
            throw ApiException(0, "Home Assistant non raggiungibile: controlla la connessione e che il componente aggiuntivo Vigilo sia avviato.")
        }
        if (code == 401) throw ApiException(401, "Abbinamento non valido: genera un nuovo QR nel componente aggiuntivo e scansionalo.")
        if (code !in 200..299) {
            val msg = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            throw ApiException(code, msg?.takeIf { it.isNotBlank() } ?: "Errore Home Assistant ($code)")
        }
        return json.decodeFromString(text)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        /** Dati letti dal QR: vigilo://pair?url=https%3A%2F%2F…&token=… */
        data class Pairing(val url: String, val token: String)

        /** Accetta il link del QR (vigilo://pair?…) oppure un URL https con ?token=… . */
        fun parsePairing(raw: String): Pairing? {
            val s = raw.trim()
            if (s.isBlank()) return null
            val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
            return parsePairing(uri.scheme, uri.host, uri.getQueryParameter("url"), uri.getQueryParameter("token"), s)
        }

        /** Versione senza android.net.Uri, usata anche dai test JVM. */
        fun parsePairing(scheme: String?, host: String?, urlParam: String?, tokenParam: String?, raw: String = ""): Pairing? {
            val token = tokenParam?.trim().orEmpty()
            if (token.length < 20) return null
            val url = when {
                scheme == "vigilo" && host == "pair" -> urlParam?.trim().orEmpty()
                scheme == "https" -> raw.substringBefore('?')
                else -> ""
            }.trimEnd('/')
            if (!url.startsWith("https://") || url.length < 12) return null
            return Pairing(url, token)
        }

        /** Converte lo stato dell'add-on nello stesso modello usato dalla modalità diretta. */
        fun toSnapshot(s: Status, now: Long = System.currentTimeMillis()): Snapshot {
            val v = s.vehicle
            val sentryOn = when (v.sentry) {
                "" -> null
                "Off" -> false
                else -> true
            }
            val conn = when {
                v.online || v.telemetryConnected -> "online"
                v.connectionState.isNotBlank() -> v.connectionState
                else -> "unknown"
            }
            return Snapshot(
                timestamp = (v.updatedAt.takeIf { it > 0 } ?: now),
                online = v.online || v.telemetryConnected,
                connectionState = conn,
                name = s.name.ifBlank { null },
                batteryLevel = v.battery?.toInt(),
                rangeKm = v.rangeKm,
                chargingState = v.charging.ifBlank { null },
                locked = v.locked,
                sentry = sentryOn,
                sentryMode = v.sentry.ifBlank { null },
                latitude = v.lat,
                longitude = v.lon,
                shiftState = v.gear.ifBlank { null },
                doorsOpen = v.doorsOpen,
                windowsOpen = v.windowsOpen,
                telemetryConnected = v.telemetryConnected,
            )
        }
    }
}
