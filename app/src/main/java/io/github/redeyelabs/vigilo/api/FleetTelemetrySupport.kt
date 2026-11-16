package io.github.redeyelabs.vigilo.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Logica pura (testabile) per gli avvisi Sentinella via Fleet Telemetry:
 * URL del JWS, corpo della richiesta, messaggi in italiano, rinnovo automatico.
 * Non stampa token né il JWS.
 */
object FleetTelemetrySupport {
    const val defaultJwsPath = "/local/vigilo/signed-config.jws"
    const val pollIntervalMs = 10_000L
    const val pollTimeoutMs = 120_000L
    val displayZone: ZoneId = ZoneId.of("Europe/Rome")

    private val json = Json { ignoreUnknownKeys = true }
    private val expFormat = DateTimeFormatter.ofPattern("d MMMM yyyy 'alle' HH:mm", Locale.ITALIAN)

    val permanentSkipReasons = setOf("missing_key", "unsupported_firmware", "unsupported_hardware")

    fun defaultJwsUrl(proxyUrl: String): String {
        val host = hostFromUrl(proxyUrl) ?: return ""
        val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "http://$hostPart$defaultJwsPath"
    }

    fun hostFromUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isBlank()) return null
        val withScheme = if (s.contains("://")) s else "https://$s"
        val after = withScheme.substringAfter("://")
        val hostPort = after.substringBefore('/')
        val host = when {
            hostPort.startsWith("[") -> hostPort.substringAfter("[").substringBefore("]")
            else -> hostPort.substringBefore(':')
        }
        return host.ifBlank { null }
    }

    fun configJwsBody(vin: String, jws: String): JsonObject = buildJsonObject {
        putJsonArray("vins") { add(vin) }
        put("token", jws.trim())
    }

    fun looksLikeJws(text: String): Boolean {
        val parts = text.trim().split('.')
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    fun jwsSha256(jws: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(jws.trim().toByteArray(Charsets.US_ASCII))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun decodeExpEpochSeconds(jws: String): Long? {
        val parts = jws.trim().split('.')
        if (parts.size < 2) return null
        val payload = decodeBase64Url(parts[1]) ?: return null
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return obj["exp"]?.jsonPrimitive?.longOrNull
            ?: obj["exp"]?.jsonPrimitive?.intOrNull?.toLong()
    }

    fun formatExp(epochSeconds: Long, zone: ZoneId = displayZone): String {
        val dt = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        return dt.format(expFormat)
    }

    fun describeSkipReason(reason: String): String = when (reason) {
        "missing_key" -> "chiave virtuale non abbinata"
        "unsupported_firmware" -> "aggiorna il software dell'auto"
        "unsupported_hardware" -> "auto non compatibile"
        else -> reason
    }

    fun describeHttpError(code: Int): String? = when (code) {
        401, 403 -> "fai di nuovo l'accesso in Impostazioni"
        else -> null
    }

    fun isPermanentSkip(reasons: Collection<String>): Boolean =
        reasons.any { it in permanentSkipReasons }

    /**
     * Rinnova se il JWS è cambiato, se l'auto non è sincronizzata o se manca la config.
     * Se l'ultimo invio dello stesso JWS è stato saltato per un motivo permanente, non riprova.
     */
    fun shouldAutoRenew(
        jwsChanged: Boolean,
        synced: Boolean?,
        configMissing: Boolean,
        lastSkipPermanent: Boolean,
        expiresSoon: Boolean = false,
    ): Boolean {
        // Mai per il solo "non sincronizzata": l'auto riceve la configurazione al prossimo risveglio,
        // reinviarla non serve e non vogliamo svegliarla.
        if (lastSkipPermanent && !jwsChanged) return false
        return jwsChanged || configMissing || expiresSoon
    }

    fun shouldRunRenewNow(charging: Boolean, wifiOrUnmetered: Boolean): Boolean =
        charging || wifiOrUnmetered

    fun formatSubmitResult(updatedVehicles: Int, skipped: Map<String, List<String>>): String {
        val parts = mutableListOf<String>()
        if (updatedVehicles > 0) {
            parts += if (updatedVehicles == 1) "Configurazione inviata a 1 veicolo."
            else "Configurazione inviata a $updatedVehicles veicoli."
        }
        if (skipped.isNotEmpty()) {
            val reasons = skipped.entries
                .filter { it.value.isNotEmpty() }
                .joinToString("; ") { describeSkipReason(it.key) }
            if (reasons.isNotBlank()) parts += "Non applicata: $reasons."
        }
        if (parts.isEmpty()) parts += "Tesla ha accettato la richiesta."
        return parts.joinToString(" ")
    }

    fun formatSyncStatus(synced: Boolean?, hostname: String?, port: Int?, missing: Boolean): String {
        if (missing && hostname.isNullOrBlank()) {
            return "Nessuna configurazione di telemetria sull'auto."
        }
        val sync = when (synced) {
            true -> "Sincronizzata: sì"
            false -> "Sincronizzata: no. L'auto non ha ancora ricevuto la configurazione"
            null -> "Stato di sincronizzazione non disponibile"
        }
        val target = when {
            !hostname.isNullOrBlank() && port != null -> "Server: $hostname:$port"
            !hostname.isNullOrBlank() -> "Server: $hostname"
            else -> null
        }
        return listOfNotNull(sync, target).joinToString(". ") + "."
    }

    fun formatErrors(errors: List<String>): String =
        if (errors.isEmpty()) "Nessun errore segnalato dall'auto."
        else errors.joinToString("\n") { "• $it" }

    fun parseSkippedVehicles(response: JsonObject?): Map<String, List<String>> {
        val skipped = response?.get("skipped_vehicles") as? JsonObject ?: return emptyMap()
        return skipped.entries.mapNotNull { (reason, value) ->
            val vins = when (value) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(value.content)
                else -> emptyList()
            }.filter { it.isNotBlank() }
            if (vins.isEmpty()) null else reason to vins
        }.toMap()
    }

    fun parseUpdatedVehicles(response: JsonObject?): Int =
        response?.get("updated_vehicles")?.jsonPrimitive?.intOrNull ?: 0

    private fun decodeBase64Url(s: String): ByteArray? = runCatching {
        val padded = s.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        Base64.getDecoder().decode(padded)
    }.getOrNull()
}

data class FleetTelemetryConfigStatus(
    val synced: Boolean? = null,
    val hostname: String? = null,
    val port: Int? = null,
    val missing: Boolean = false,
)

data class FleetTelemetrySubmitResult(
    val updatedVehicles: Int,
    val skipped: Map<String, List<String>>,
) {
    val permanentSkip: Boolean
        get() = updatedVehicles == 0 && FleetTelemetrySupport.isPermanentSkip(skipped.keys)
}
