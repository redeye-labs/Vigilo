package io.github.redeyelabs.vigilo.api

import io.github.redeyelabs.vigilo.data.Prefs
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.UnknownServiceException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: Int, msg: String) : Exception(msg)

@Serializable
data class VehicleInfo(val vin: String, val name: String, val state: String)

/** Istantanea dello stato dell'auto, salvata come JSON per i confronti tra un controllo e l'altro. */
@Serializable
data class Snapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val online: Boolean = false,
    val connectionState: String = "unknown",
    val name: String? = null,
    val batteryLevel: Int? = null,
    val rangeKm: Double? = null,
    val chargingState: String? = null,
    val chargeLimit: Int? = null,
    val minutesToFull: Int? = null,
    val locked: Boolean? = null,
    val sentry: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val heading: Int? = null,
    val shiftState: String? = null,
    val doorsOpen: Boolean? = null,
    val trunkOpen: Boolean? = null,
    val frunkOpen: Boolean? = null,
    val windowsOpen: Boolean? = null,
    val userPresent: Boolean? = null,
    val insideTemp: Double? = null,
    val outsideTemp: Double? = null,
    val odometerKm: Double? = null,
    val softwareVersion: String? = null,
    /** true se Tesla ha negato la posizione perché manca lo scope vehicle_location. */
    val locationScopeMissing: Boolean = false,
    /** Stato dettagliato da Home Assistant: Off, Idle, Armed, Aware, Panic, Quiet. */
    val sentryMode: String? = null,
    /** true se l'auto è collegata in tempo reale al componente aggiuntivo (Fleet Telemetry). */
    val telemetryConnected: Boolean? = null,
) {
    val parked: Boolean get() = shiftState == null || shiftState == "P"
}

private const val MILES_TO_KM = 1.609344

const val WAKING_MESSAGE = "Sveglio l'auto…"
const val VEHICLE_UNREACHABLE_MESSAGE =
    "L'auto non risponde: potrebbe avere poco segnale. Riprova tra poco."

/** Classificazione errori e payload dei comandi, esposti anche ai test. */
object VehicleCommandSupport {
    const val wakePollMs = 3_000L
    const val wakeTimeoutMs = 45_000L
    const val maxRetries = 2

    fun sentryBody(on: Boolean): JsonObject = buildJsonObject { put("on", on) }

    fun isTransientUnreachable(error: Throwable): Boolean {
        val code = (error as? ApiException)?.code
        val msg = listOfNotNull(error.message, error.cause?.message).joinToString(" ").lowercase()
        if (code == 408) return true
        if (msg.contains("context deadline exceeded")) return true
        if (msg.contains("vehicle unavailable") || msg.contains("vehicle_unavailable")) return true
        if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("sockettimeout")) return true
        return false
    }

    fun userMessage(error: Throwable): String =
        if (isTransientUnreachable(error) ||
            (error.message ?: "").contains("non si è svegliata") ||
            (error.message ?: "").contains("non risponde")
        ) VEHICLE_UNREACHABLE_MESSAGE
        else error.message ?: "Comando non riuscito"
}

/**
 * Client minimale per la Tesla Fleet API.
 * Lettura dati: direttamente su Fleet API.
 * Comandi: tramite tesla-http-proxy se configurato (necessario per Model 3 2021+: comandi firmati
 * con la chiave virtuale); altrimenti tentativo diretto, che l'auto rifiuterà se richiede la firma.
 */
class TeslaApi(private val p: Prefs) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base get() = p.region.baseUrl

    /** Aggiornamenti in italiano per l'interfaccia (es. "Sveglio l'auto…"). */
    var onProgress: ((String) -> Unit)? = null

    private fun get(path: String): JsonObject {
        val token = TeslaAuth.validAccessToken(p)
        val req = Request.Builder().url(base + path).header("Authorization", "Bearer $token").get().build()
        Http.client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, describe(r.code, txt))
            return json.parseToJsonElement(txt).jsonObject
        }
    }

    private fun post(baseUrl: String, path: String, body: JsonObject, viaProxy: Boolean): JsonObject {
        val token = TeslaAuth.validAccessToken(p)
        val client = if (viaProxy) Http.proxyClient(p.proxyCertSha256) else Http.client
        val req = Request.Builder().url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, describe(r.code, txt))
            return json.parseToJsonElement(txt).jsonObject
        }
    }

    private fun describe(code: Int, body: String): String = when (code) {
        401 -> "Token non valido o scaduto (401): rifai il login. $body"
        403 -> "Accesso negato (403): controlla scope e registrazione partner. $body"
        408 -> "Auto addormentata o non raggiungibile (408)"
        412 -> "Registrazione partner mancante per questa regione (412). $body"
        421 -> "Regione errata (421): cambia regione nelle impostazioni. $body"
        429 -> "Troppe richieste (429): limite Tesla raggiunto, riprova più tardi"
        else -> "Errore Fleet API ($code): $body"
    }

    fun listVehicles(): List<VehicleInfo> =
        get("/api/1/vehicles")["response"]!!.jsonArray.map {
            val o = it.jsonObject
            VehicleInfo(
                vin = o.s("vin") ?: "",
                name = o.s("display_name") ?: "Tesla",
                state = o.s("state") ?: "unknown",
            )
        }

    /** Stato di connessione senza svegliare l'auto: online / asleep / offline. */
    fun connectionState(vin: String): String =
        get("/api/1/vehicles/$vin")["response"]!!.jsonObject.s("state") ?: "unknown"

    fun vehicleData(vin: String): Snapshot {
        // Dal firmware 2023.38 la posizione arriva solo chiedendo location_data, che richiede lo scope
        // vehicle_location. Se manca (403), si ripete la lettura senza posizione: il resto funziona.
        val withLocation = "charge_state%3Bclimate_state%3Bdrive_state%3Blocation_data%3Bvehicle_state"
        val withoutLocation = "charge_state%3Bclimate_state%3Bdrive_state%3Bvehicle_state"
        var scopeMissing = false
        val r = try {
            get("/api/1/vehicles/$vin/vehicle_data?endpoints=$withLocation")["response"]!!.jsonObject
        } catch (e: ApiException) {
            if (e.code == 403 && (e.message ?: "").contains("location", ignoreCase = true)) {
                scopeMissing = true
                get("/api/1/vehicles/$vin/vehicle_data?endpoints=$withoutLocation")["response"]!!.jsonObject
            } else throw e
        }
        val cs = r.o("charge_state"); val vs = r.o("vehicle_state")
        val ds = r.o("drive_state"); val cl = r.o("climate_state")
        fun open(vararg keys: String, src: JsonObject?) =
            if (src == null) null else keys.any { (src.i(it) ?: 0) != 0 }
        return Snapshot(
            online = true,
            connectionState = r.s("state") ?: "online",
            name = r.s("display_name") ?: vs?.s("vehicle_name"),
            batteryLevel = cs?.i("battery_level"),
            rangeKm = cs?.d("battery_range")?.let { it * MILES_TO_KM },
            chargingState = cs?.s("charging_state"),
            chargeLimit = cs?.i("charge_limit_soc"),
            minutesToFull = cs?.i("minutes_to_full_charge"),
            locked = vs?.b("locked"),
            sentry = vs?.b("sentry_mode"),
            latitude = ds?.d("latitude"),
            longitude = ds?.d("longitude"),
            heading = ds?.i("heading"),
            shiftState = ds?.s("shift_state"),
            doorsOpen = open("df", "dr", "pf", "pr", src = vs),
            trunkOpen = vs?.i("rt")?.let { it != 0 },
            frunkOpen = vs?.i("ft")?.let { it != 0 },
            windowsOpen = open("fd_window", "fp_window", "rd_window", "rp_window", src = vs),
            userPresent = vs?.b("is_user_present"),
            insideTemp = cl?.d("inside_temp"),
            outsideTemp = cl?.d("outside_temp"),
            odometerKm = vs?.d("odometer")?.let { it * MILES_TO_KM },
            softwareVersion = vs?.s("car_version"),
            locationScopeMissing = scopeMissing,
        )
    }

    /** True se GET /vehicles/{vin} riporta state=online. Non sveglia l'auto. */
    fun isOnline(vin: String): Boolean =
        runCatching { connectionState(vin) }.getOrNull()?.equals("online", ignoreCase = true) == true

    /**
     * Se l'auto non è online, invia wake_up e interroga lo stato ogni ~3 s fino a ~45 s.
     * Le sveglie costano (Fleet API): usare solo per i comandi.
     */
    suspend fun wakeAndWait(vin: String): Boolean {
        if (isOnline(vin)) return true
        onProgress?.invoke(WAKING_MESSAGE)
        post(base, "/api/1/vehicles/$vin/wake_up", JsonObject(emptyMap()), viaProxy = false)
        val deadline = System.currentTimeMillis() + VehicleCommandSupport.wakeTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(VehicleCommandSupport.wakePollMs)
            onProgress?.invoke(WAKING_MESSAGE)
            if (isOnline(vin)) return true
        }
        return false
    }

    /**
     * Normalizza l'URL del proxy e, se manca l'impronta, la legge al primo collegamento
     * (trust on first use) e la salva. Restituisce l'URL da usare, o null se il proxy non è impostato.
     */
    fun ensureProxyReady(): String? {
        val url = Http.normalizeProxyUrl(p.proxyUrl)
        if (url.isBlank()) return null
        if (url != p.proxyUrl) p.proxyUrl = url
        if (p.proxyCertSha256.isBlank()) {
            try {
                p.proxyCertSha256 = Http.captureLeafSha256(url)
            } catch (e: Exception) {
                throw IOException(Http.unreachableHint(e), e)
            }
        }
        return url
    }

    /**
     * Verifica il proxy: normalizza l'indirizzo, memorizza l'impronta se manca, fa una richiesta
     * innocua (GET /api/1/vehicles) e restituisce un messaggio in italiano.
     */
    fun verifyProxy(rawUrl: String? = null): String {
        val url = Http.normalizeProxyUrl(rawUrl ?: p.proxyUrl)
        if (url.isBlank()) throw IllegalArgumentException("Inserisci l'indirizzo di Home Assistant.")
        p.proxyUrl = url
        val firstTime = p.proxyCertSha256.isBlank()
        try {
            if (firstTime) {
                p.proxyCertSha256 = Http.captureLeafSha256(url)
            } else {
                Http.probeOnce(Http.proxyClient(p.proxyCertSha256), url)
            }
        } catch (e: Exception) {
            throw IOException(Http.unreachableHint(e), e)
        }
        val short = Http.shortFingerprint(p.proxyCertSha256)
        val baseMsg = if (firstTime)
            "Proxy collegato. Certificato memorizzato ($short)."
        else
            "Proxy verificato. Certificato riconosciuto ($short)."
        return try {
            val token = runCatching { TeslaAuth.validAccessToken(p) }.getOrNull()
            val req = Request.Builder().url("$url/api/1/vehicles").get()
            if (!token.isNullOrBlank()) req.header("Authorization", "Bearer $token")
            Http.proxyClient(p.proxyCertSha256).newCall(req.build()).execute().use { r ->
                when {
                    r.isSuccessful -> "$baseMsg Il proxy ha risposto correttamente."
                    r.code == 401 || r.code == 403 ->
                        "$baseMsg Accedi con Tesla se vuoi usare i comandi."
                    else -> "$baseMsg (risposta HTTP ${r.code})"
                }
            }
        } catch (e: Exception) {
            if (Http.isCertificateMismatch(e)) throw IOException(Http.unreachableHint(e), e)
            // TLS già riuscito: una risposta HTTP inattesa non invalida il collegamento.
            "$baseMsg La connessione TLS è riuscita."
        }
    }

    /**
     * Prima del comando controlla lo stato; se l'auto dorme la sveglia (fino a ~45 s).
     * In caso di 408 / 500 "context deadline exceeded" / "vehicle unavailable" riprova
     * fino a 2 volte, con una nuova sveglia e un attesa crescente.
     */
    suspend fun command(vin: String, name: String, body: JsonObject = JsonObject(emptyMap())): String {
        var last: Throwable? = null
        val attempts = 1 + VehicleCommandSupport.maxRetries
        for (attempt in 0 until attempts) {
            if (last != null) {
                onProgress?.invoke(WAKING_MESSAGE)
                delay(VehicleCommandSupport.wakePollMs * (attempt))
            }
            if (!wakeAndWait(vin)) {
                throw ApiException(0, VEHICLE_UNREACHABLE_MESSAGE)
            }
            try {
                return sendCommand(vin, name, body)
            } catch (e: Exception) {
                last = e
                if (!VehicleCommandSupport.isTransientUnreachable(e) || attempt == attempts - 1) {
                    throw if (VehicleCommandSupport.isTransientUnreachable(e))
                        ApiException((e as? ApiException)?.code ?: 0, VEHICLE_UNREACHABLE_MESSAGE)
                    else e
                }
            }
        }
        throw ApiException(0, VEHICLE_UNREACHABLE_MESSAGE)
    }

    private fun sendCommand(vin: String, name: String, body: JsonObject): String {
        val baseUrl = ensureProxyReady()
        val useProxy = baseUrl != null
        val res = post(baseUrl ?: base, "/api/1/vehicles/$vin/command/$name", body, viaProxy = useProxy)
        val resp = res["response"]?.takeIf { it !is JsonNull }?.jsonObject
        val ok = resp?.b("result") ?: false
        if (!ok) {
            val reason = resp?.s("reason").orEmpty()
            val hint = if (!useProxy && (reason.contains("signed", true) || reason.contains("protocol", true)))
                " – la Model 3 richiede comandi firmati: configura il tesla-http-proxy" else ""
            throw ApiException(200, "Comando $name rifiutato: $reason$hint")
        }
        return "Comando $name eseguito"
    }


    /** Scarica il JWS firmato da Home Assistant (testo in chiaro, non è un segreto). Non lo registra. */
    fun fetchSignedConfigJws(url: String): String {
        val target = url.trim()
        if (target.isBlank()) throw ApiException(0, "Inserisci l'indirizzo del file di configurazione.")
        try {
            Http.client.newCall(Request.Builder().url(target).get().build()).execute().use { r ->
                val txt = r.body?.string().orEmpty().trim()
                if (!r.isSuccessful) {
                    throw ApiException(
                        r.code,
                        "Configurazione non trovata su Home Assistant (${r.code}). Controlla l'indirizzo e che Tailscale sia attivo.",
                    )
                }
                if (!FleetTelemetrySupport.looksLikeJws(txt)) {
                    throw ApiException(0, "Il file scaricato non è una configurazione firmata valida.")
                }
                FleetTelemetrySupport.decodeExpEpochSeconds(txt)?.let { p.telemetryJwsExp = it }
                return txt
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: UnknownServiceException) {
            throw IOException(
                "Connessione HTTP non consentita verso questo indirizzo. Controlla che Tailscale sia attivo e che l'indirizzo sia corretto.",
                e,
            )
        } catch (e: Exception) {
            if (Http.isUnreachable(e)) {
                throw IOException(
                    "Impossibile raggiungere Home Assistant. Controlla che Tailscale sia attivo sul telefono e che l'indirizzo sia corretto.",
                    e,
                )
            }
            throw e
        }
    }

    fun submitFleetTelemetryConfig(vin: String, jws: String): FleetTelemetrySubmitResult {
        val body = FleetTelemetrySupport.configJwsBody(vin, jws)
        val res = telemetryCall { post(base, "/api/1/vehicles/fleet_telemetry_config_jws", body, viaProxy = false) }
        val response = res["response"]?.takeIf { it !is JsonNull }?.jsonObject
        return FleetTelemetrySubmitResult(
            updatedVehicles = FleetTelemetrySupport.parseUpdatedVehicles(response),
            skipped = FleetTelemetrySupport.parseSkippedVehicles(response),
        )
    }

    fun fleetTelemetryConfig(vin: String): FleetTelemetryConfigStatus {
        val res = try {
            telemetryCall { get("/api/1/vehicles/$vin/fleet_telemetry_config") }
        } catch (e: ApiException) {
            if (e.code == 404) return FleetTelemetryConfigStatus(missing = true)
            throw e
        }
        val response = res["response"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: return FleetTelemetryConfigStatus(missing = true)
        val config = response.o("config")
        val hostname = config?.s("hostname")
        val port = config?.i("port")
        val missing = hostname.isNullOrBlank() && config == null
        return FleetTelemetryConfigStatus(
            synced = response.b("synced"),
            hostname = hostname,
            port = port,
            missing = missing,
        )
    }

    fun fleetTelemetryErrors(vin: String): List<String> {
        val res = try {
            telemetryCall { get("/api/1/vehicles/$vin/fleet_telemetry_errors") }
        } catch (e: ApiException) {
            if (e.code == 404) return emptyList()
            throw e
        }
        val el = res["response"] ?: return emptyList()
        val items = when (el) {
            is JsonNull -> emptyList()
            is kotlinx.serialization.json.JsonArray -> el
            is JsonObject -> (el["errors"] as? kotlinx.serialization.json.JsonArray)
                ?: (el["response"] as? kotlinx.serialization.json.JsonArray)
                ?: kotlinx.serialization.json.JsonArray(listOf(el))
            else -> kotlinx.serialization.json.JsonArray(listOf(el))
        }
        return items.mapNotNull { item ->
            when (item) {
                is JsonObject -> {
                    val name = item.s("name") ?: item.s("error") ?: item.s("type")
                    val body = item.s("body") ?: item.s("message") ?: item.s("error")
                    val ts = item.s("timestamp") ?: item.s("created_at")
                    listOfNotNull(name, body, ts?.let { "($it)" }).joinToString(" ").ifBlank { null }
                }
                is kotlinx.serialization.json.JsonPrimitive -> item.content.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    fun telemetryStatusMessage(vin: String): String {
        val cfg = fleetTelemetryConfig(vin)
        val errors = fleetTelemetryErrors(vin)
        return listOf(
            FleetTelemetrySupport.formatSyncStatus(cfg.synced, cfg.hostname, cfg.port, cfg.missing),
            FleetTelemetrySupport.formatErrors(errors),
        ).joinToString("\n")
    }

    /**
     * Sveglia l'auto, invia il JWS e interroga lo stato ogni 10 s fino a 2 minuti.
     * Chiamata diretta alla Fleet API (non passa dal proxy).
     */
    suspend fun enableSentryAlerts(vin: String, jwsUrl: String): String {
        if (vin.isBlank()) throw ApiException(0, "Seleziona un veicolo in Impostazioni.")
        onProgress?.invoke("Scarico la configurazione…")
        val jws = fetchSignedConfigJws(jwsUrl)
        if (!wakeAndWait(vin)) throw ApiException(0, VEHICLE_UNREACHABLE_MESSAGE)
        onProgress?.invoke("Invio la configurazione a Tesla…")
        val submitted = submitFleetTelemetryConfig(vin, jws)
        p.lastEnrolledJwsHash = FleetTelemetrySupport.jwsSha256(jws)
        p.lastEnrollPermanentSkip = submitted.permanentSkip
        val submitText = FleetTelemetrySupport.formatSubmitResult(submitted.updatedVehicles, submitted.skipped)
        if (submitted.permanentSkip) return submitText
        var status = fleetTelemetryConfig(vin)
        val deadline = System.currentTimeMillis() + FleetTelemetrySupport.pollTimeoutMs
        while (status.synced != true && System.currentTimeMillis() < deadline) {
            onProgress?.invoke("In attesa che l'auto riceva la configurazione…")
            delay(FleetTelemetrySupport.pollIntervalMs)
            status = fleetTelemetryConfig(vin)
        }
        val syncText = FleetTelemetrySupport.formatSyncStatus(status.synced, status.hostname, status.port, status.missing)
        return "$submitText $syncText"
    }

    /**
     * Rinnovo automatico: scarica il JWS e lo reinvia solo se è cambiato, se la configurazione manca
     * o se scade entro 30 giorni. Non sveglia mai l'auto. Restituisce null se non c'è nulla da fare.
     */
    suspend fun autoRenewSentryAlerts(vin: String, jwsUrl: String): String? {
        if (vin.isBlank()) return null
        val jws = fetchSignedConfigJws(jwsUrl)
        val hash = FleetTelemetrySupport.jwsSha256(jws)
        val changed = hash != p.lastEnrolledJwsHash
        val status = fleetTelemetryConfig(vin)
        if (!FleetTelemetrySupport.shouldAutoRenew(
                jwsChanged = changed,
                synced = status.synced,
                configMissing = status.missing,
                lastSkipPermanent = p.lastEnrollPermanentSkip && !changed,
                expiresSoon = p.telemetryJwsExp > 0 &&
                    p.telemetryJwsExp - System.currentTimeMillis() / 1000 < 30L * 24 * 3600,
            )
        ) return null
        // Nessuna sveglia: Tesla accoda la configurazione e l'auto la riceve al prossimo risveglio.
        val submitted = submitFleetTelemetryConfig(vin, jws)
        p.lastEnrolledJwsHash = hash
        p.lastEnrollPermanentSkip = submitted.permanentSkip
        val submitText = FleetTelemetrySupport.formatSubmitResult(submitted.updatedVehicles, submitted.skipped)
        val after = fleetTelemetryConfig(vin)
        val syncText = FleetTelemetrySupport.formatSyncStatus(after.synced, after.hostname, after.port, after.missing)
        return "$submitText $syncText"
    }

    private fun telemetryCall(block: () -> JsonObject): JsonObject = try {
        block()
    } catch (e: ApiException) {
        val friendly = FleetTelemetrySupport.describeHttpError(e.code)
        if (friendly != null) throw ApiException(e.code, friendly) else throw e
    }

    suspend fun setSentry(vin: String, on: Boolean): String {
        command(vin, "set_sentry_mode", VehicleCommandSupport.sentryBody(on))
        return if (on) "Sentinella attivata" else "Sentinella disattivata"
    }
    suspend fun honk(vin: String) = command(vin, "honk_horn")
    suspend fun flash(vin: String) = command(vin, "flash_lights")
    suspend fun lock(vin: String) = command(vin, "door_lock")
    suspend fun unlock(vin: String) = command(vin, "door_unlock")
}

// --- helper JSON tolleranti ai null ---
private fun JsonObject.o(k: String): JsonObject? = (this[k] as? JsonObject)
private fun JsonObject.p(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
private fun JsonObject.s(k: String): String? = p(k)?.content
private fun JsonObject.i(k: String): Int? = p(k)?.intOrNull ?: p(k)?.doubleOrNull?.toInt()
private fun JsonObject.d(k: String): Double? = p(k)?.doubleOrNull
private fun JsonObject.b(k: String): Boolean? = p(k)?.booleanOrNull
@Suppress("unused") private fun JsonElement.asObj() = jsonObject
