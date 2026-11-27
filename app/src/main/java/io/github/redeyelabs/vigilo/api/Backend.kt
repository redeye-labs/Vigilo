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
        @SerialName("inside_temp") val insideTemp: Double? = null,
        @SerialName("outside_temp") val outsideTemp: Double? = null,
        @SerialName("odometer_km") val odometerKm: Double? = null,
        @SerialName("trunk_open") val trunkOpen: Boolean? = null,
        @SerialName("frunk_open") val frunkOpen: Boolean? = null,
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

    /** Azioni automatiche eseguite dal server (Vigilo 1.0.7+). */
    @Serializable
    data class Actions(
        val enabled: Boolean = false,
        @SerialName("on_aware") val onAware: Boolean = true,
        @SerialName("on_panic") val onPanic: Boolean = false,
        val flash: Boolean = true,
        val honk: Boolean = false,
        @SerialName("cooldown_sec") val cooldownSec: Int = 300,
        @SerialName("hours_only") val hoursOnly: Boolean = false,
        @SerialName("from_min") val fromMin: Int = 22 * 60,
        @SerialName("to_min") val toMin: Int = 7 * 60,
        val tz: String = "",
    )

    @Serializable
    private data class ActionsResponse(val ok: Boolean = false, val actions: Actions = Actions())

    /** Regola del programma Sentinella (giorni: bit0 = lunedì … bit6 = domenica). */
    @Serializable
    data class Rule(
        val id: Long = 0,
        val hour: Int = 22,
        val minute: Int = 0,
        @SerialName("days_mask") val daysMask: Int = 0b0011111,
        val enable: Boolean = true,
        val active: Boolean = true,
    )

    @Serializable
    data class Schedules(val tz: String = "", val rules: List<Rule> = emptyList())

    @Serializable
    private data class SchedulesResponse(val ok: Boolean = false, val schedules: Schedules = Schedules())

    @Serializable
    private data class CommandResponse(val ok: Boolean = false, val message: String = "", val status: Status? = null)

    @Serializable
    private data class ErrorBody(val ok: Boolean = false, val error: String = "")

    /** Vigilo 2 preview: read-only endpoints, available only on updated personal servers. */
    @Serializable
    data class V2Module(
        val id: String = "",
        val enabled: Boolean = false,
        val available: Boolean = false,
    )

    @Serializable
    data class V2Capabilities(
        val ok: Boolean = false,
        val schema: Int = 0,
        val modules: List<V2Module> = emptyList(),
        @SerialName("vehicle_configured") val vehicleConfigured: Boolean = false,
    )

    @Serializable
    data class V2Connection(
        val status: String = "",
        val details: String = "",
        @SerialName("last_seen_at") val lastSeenAt: String? = null,
    )

    @Serializable
    data class V2Diagnostics(
        val ok: Boolean = false,
        @SerialName("vehicle_id") val vehicleID: String = "",
        @SerialName("telemetry") val telemetry: V2Connection = V2Connection(),
        @SerialName("permission_status") val permissionStatus: String = "not_verified",
        @SerialName("server_reachable") val serverReachable: Boolean = false,
    )

    @Serializable
    data class V2Incident(
        @SerialName("vehicle_id") val vehicleID: String = "",
        @SerialName("started_at") val startedAt: String = "",
        @SerialName("ended_at") val endedAt: String = "",
        @SerialName("event_count") val eventCount: Int = 0,
        val panic: Boolean = false,
    )

    @Serializable
    data class V2Security(
        val ok: Boolean = false,
        @SerialName("vehicle_id") val vehicleID: String = "",
        val incidents: List<V2Incident> = emptyList(),
        val scope: String = "",
    )

    @Serializable
    data class V2Report(
        @SerialName("vehicle_id") val vehicleID: String = "",
        val from: String = "",
        val to: String = "",
        @SerialName("total_events") val totalEvents: Int = 0,
        @SerialName("aware_events") val awareEvents: Int = 0,
        @SerialName("panic_events") val panicEvents: Int = 0,
        @SerialName("grouped_incidents") val groupedIncidents: Int = 0,
        val coverage: String = "",
    )

    @Serializable
    data class V2ReportResponse(
        val ok: Boolean = false,
        val report: V2Report = V2Report(),
    )

    @Serializable
    data class V2Measurement(
        @SerialName("vehicle_id") val vehicleID: String = "",
        val signal: String = "",
        val value: Double = 0.0,
        val unit: String = "",
        val source: String = "",
        val quality: String = "",
        @SerialName("received_at") val receivedAt: String = "",
    )

    @Serializable
    data class V2MeasurementsResponse(
        val ok: Boolean = false,
        val samples: List<V2Measurement> = emptyList(),
        val count: Int = 0,
    )

    fun v2BatterySamples(): List<V2Measurement> =
        get<V2MeasurementsResponse>("/api/v2/battery/latest").samples

    fun v2SecurityReport(days: Int = 7): V2Report =
        get<V2ReportResponse>("/api/v2/security/report?days=${days.coerceIn(1, 90)}").report

    @Serializable
    data class V2ModuleChange(
        val ok: Boolean = false,
        val module: String = "",
        val enabled: Boolean = false,
    )

    fun v2SetModule(module: String, enabled: Boolean): V2ModuleChange {
        require(module in setOf("security", "diagnostics", "backup", "commands", "battery", "charging", "automation", "trips"))
        val body = """{"module":"$module","enabled":$enabled}""".toRequestBody(JSON)
        return send("PUT", "/api/v2/modules", body)
    }

    fun v2ManualParameterCommand(action: String, value: Double): String {
        require(value.isFinite())
        require(
            when (action) {
                "charge_limit" -> value in 50.0..100.0 && value % 1.0 == 0.0
                "climate_temperature" -> value in 15.0..30.0
                "charging_amps" -> value in 5.0..32.0 && value % 1.0 == 0.0
                else -> false
            }
        ) { "Parametro Tesla non valido." }
        val body = """{"command":"$action","value":$value}""".toRequestBody(JSON)
        val response: CommandResponse = send(
            "POST", "/api/v2/commands", body, slow = true, confirmed = true,
        )
        return response.message
    }

    fun v2ManualCommand(action: String): String {
        require(action in setOf("climate_on", "climate_off", "charge_start", "charge_stop",
            "charge_port_open", "charge_port_close", "trunk_rear", "trunk_front"))
        val body = """{"command":"$action"}""".toRequestBody(JSON)
        val response: CommandResponse = send("POST", "/api/v2/commands", body, slow = true, confirmed = true)
        return response.message
    }

    @Serializable
    data class V2BatteryObservations(
        @SerialName("nominal_full_pack_kwh") val fullPackKWh: Double? = null,
        @SerialName("energy_remaining_kwh") val remainingKWh: Double? = null,
        @SerialName("rated_range_km") val ratedRangeKm: Double? = null,
        @SerialName("charge_percent") val chargePercent: Double? = null,
        @SerialName("health_percent") val healthPercent: Double? = null,
        @SerialName("temp_min_c") val tempMinC: Double? = null,
        @SerialName("temp_max_c") val tempMaxC: Double? = null,
        @SerialName("equivalent_charge_cycles") val equivalentChargeCycles: Double? = null,
        @SerialName("rated_full_range_km") val ratedFullRangeKm: Double? = null,
        @SerialName("latest_received_at") val latestReceivedAt: String = "",
        val stale: Boolean = false,
        val note: String = "",
    )

    @Serializable
    data class V2BatteryResponse(
        val ok: Boolean = false, val battery: V2BatteryObservations = V2BatteryObservations(),
    )

    @Serializable
    data class V2ChargingSession(
        @SerialName("first_seen") val firstSeen: String = "",
        @SerialName("last_seen") val lastSeen: String = "",
        @SerialName("battery_energy_kwh") val batteryKWh: Double = 0.0,
        @SerialName("energy_source") val energySource: String = "",
        val coverage: String = "",
        @SerialName("estimated_cost_eur") val costEUR: Double? = null,
        @SerialName("price_source") val priceSource: String? = null,
    )

    @Serializable
    data class V2ChargingResponse(
        val ok: Boolean = false, val sessions: List<V2ChargingSession> = emptyList(),
        val coverage: String = "",
    )

    @Serializable
    data class V2TariffResponse(
        val ok: Boolean = false, @SerialName("price_eur_per_kwh") val price: Double? = null,
        val source: String = "",
    )

    @Serializable
    data class V2TripObservation(
        @SerialName("first_seen") val firstSeen: String = "",
        @SerialName("last_seen") val lastSeen: String = "",
        @SerialName("distance_km") val distanceKm: Double = 0.0,
        val method: String = "",
        val coverage: String = "",
    )

    @Serializable
    data class V2TripResponse(
        val ok: Boolean = false, val observations: List<V2TripObservation> = emptyList(),
        val coverage: String = "",
    )

    @Serializable
    data class V2TiresResponse(
        val ok: Boolean = false,
        val readings: List<V2Measurement> = emptyList(),
        val unit: String = "bar",
    )

    fun v2TireSamples(): List<V2Measurement> =
        get<V2TiresResponse>("/api/v2/diagnostics/tires").readings

    @Serializable
    data class V2CapacityTrend(
        @SerialName("first_bms_kwh") val firstKWh: Double = 0.0,
        @SerialName("latest_bms_kwh") val latestKWh: Double = 0.0,
        @SerialName("percent_change") val percentChange: Double = 0.0,
        @SerialName("sample_count") val sampleCount: Int = 0,
        val note: String = "",
    )

    @Serializable
    data class V2CapacityTrendResponse(
        val ok: Boolean = false,
        val available: Boolean = false,
        val note: String = "",
        val trend: V2CapacityTrend? = null,
    )

    fun v2CapacityTrend(): V2CapacityTrendResponse = get("/api/v2/battery/trend")

    fun v2BatterySummary(): V2BatteryObservations =
        get<V2BatteryResponse>("/api/v2/battery/summary").battery

    fun v2ChargingSessions(): List<V2ChargingSession> =
        get<V2ChargingResponse>("/api/v2/charging/sessions").sessions

    @Serializable
    data class V2ObservedEnergy(
        @SerialName("driving_kwh") val drivingKWh: Double? = null,
        @SerialName("driving_km") val drivingKm: Double? = null,
        @SerialName("wh_per_km") val whPerKm: Double? = null,
        @SerialName("parked_kwh") val parkedKWh: Double? = null,
        @SerialName("sentry_kwh") val sentryKWh: Double? = null,
        @SerialName("parked_hours") val parkedHours: Double? = null,
        @SerialName("sentry_hours") val sentryHours: Double? = null,
        val note: String = "",
    )
    @Serializable
    data class V2ObservedEnergyResponse(val report: V2ObservedEnergy = V2ObservedEnergy())
    fun v2ObservedEnergy(days: Int = 30): V2ObservedEnergy =
        get<V2ObservedEnergyResponse>("/api/v2/trips/energy?days=${days.coerceIn(1,90)}").report

    fun v2TripObservations(): List<V2TripObservation> =
        get<V2TripResponse>("/api/v2/trips/observations").observations

    fun v2ChargingTariff(): Double? = get<V2TariffResponse>("/api/v2/charging/tariff").price

    fun v2SetChargingTariff(price: Double?): Double? {
        require(price == null || (price.isFinite() && price in 0.0..10.0))
        val value = price?.toString() ?: "null"
        val body = """{"price_eur_per_kwh":$value}""".toRequestBody(JSON)
        return send<V2TariffResponse>("PUT", "/api/v2/charging/tariff", body).price
    }

    @Serializable
    data class V2AutomationRule(
        val id: String = "",
        @SerialName("vehicle_id") val vehicleID: String = "",
        val enabled: Boolean = false,
        val condition: String = "soc_below",
        val threshold: Double = 0.0,
        @SerialName("cooldown_sec") val cooldownSec: Int = 3600,
    )

    @Serializable
    data class V2AutomationsResponse(
        val ok: Boolean = false,
        val enabled: Boolean = false,
        val rules: List<V2AutomationRule> = emptyList(),
    )

    @Serializable
    private data class V2Operation(val ok: Boolean = false)

    /** Portable non-secret export. This is not a full Home Assistant/Docker backup. */
    fun v2BackupExportJson(): String {
        val element: kotlinx.serialization.json.JsonElement = get("/api/v2/backup/export")
        return element.toString()
    }
    /** Read CSV directly; unlike all other endpoints, response is not JSON. */
    fun v2SecurityCsv(): String {
        require(baseUrl.startsWith("https://"))
        val request = Request.Builder()
            .url(baseUrl + "/api/v2/security/export.csv")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/csv")
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code == 401) throw ApiException(401, "Abbinamento Vigilo scaduto.")
            if (!response.isSuccessful) throw ApiException(response.code, "CSV non disponibile.")
            return response.body?.string().orEmpty()
        }
    }


    fun v2Automations(): V2AutomationsResponse = get("/api/v2/automations")

    fun v2SaveAutomation(rule: V2AutomationRule) {
        require(rule.id in setOf("soc-low", "sentry-panic", "parked-away"))
        require(rule.condition in setOf("soc_below", "sentry_panic", "away_from_home"))
        require(rule.cooldownSec in 300..86400)
        val body = json.encodeToString(V2AutomationRule.serializer(), rule).toRequestBody(JSON)
        val result: V2Operation = send("PUT", "/api/v2/automations", body)
        require(result.ok) { "Il server non ha confermato la regola." }
    }

    fun v2Capabilities(): V2Capabilities = get("/api/v2/capabilities")
    fun v2Diagnostics(): V2Diagnostics = get("/api/v2/diagnostics")
    fun v2Security(limit: Int = 200): V2Security =
        get("/api/v2/security/incidents?limit=${limit.coerceIn(1, 1000)}")

    fun status(): Status = get("/api/v1/status")
    /** Lettura dalla Fleet API solo se l'auto è già sveglia (l'add-on non la sveglia). */
    fun refresh(): Status = send("POST", "/api/v1/refresh", "{}".toRequestBody(JSON))
    fun events(after: Long, limit: Int = 200): List<Event> = get<Events>("/api/v1/events?after=$after&limit=$limit").events
    fun health(deep: Boolean): List<Check> = get<Health>("/api/v1/health" + if (deep) "?deep=1" else "").checks
    fun settings(): SettingsResponse = get("/api/v1/settings")
    fun saveSettings(s: Settings): SettingsResponse =
        send("PUT", "/api/v1/settings", json.encodeToString(Settings.serializer(), s).toRequestBody(JSON))

    /** Le chiamate seguenti rispondono 404 sui server più vecchi di 1.0.7 (vedi [isUnsupported]). */
    fun actions(): Actions = get<ActionsResponse>("/api/v1/actions").actions
    fun saveActions(a: Actions): Actions =
        send<ActionsResponse>("PUT", "/api/v1/actions", json.encodeToString(Actions.serializer(), a).toRequestBody(JSON)).actions
    fun schedules(): Schedules = get<SchedulesResponse>("/api/v1/schedules").schedules
    fun saveSchedules(s: Schedules): Schedules =
        send<SchedulesResponse>("PUT", "/api/v1/schedules", json.encodeToString(Schedules.serializer(), s).toRequestBody(JSON)).schedules

    /** Comandi: sentry_on, sentry_off, lock, unlock, honk, flash. Restituisce il messaggio dell'add-on. */
    fun command(name: String): String {
        val r: CommandResponse = send("POST", "/api/v1/command", """{"command":"$name"}""".toRequestBody(JSON), slow = true)
        return r.message.ifBlank { "Fatto" }
    }

    private inline fun <reified T> get(path: String): T = send("GET", path, null)

    private inline fun <reified T> send(method: String, path: String, body: RequestBody?, slow: Boolean = false, confirmed: Boolean = false): T {
        require(baseUrl.startsWith("https://")) { "Server Vigilo non abbinato: inquadra il QR dell'ultimo passo della configurazione di Vigilo." }
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, body)
            .apply { if (confirmed) header("X-Vigilo-Confirm", "yes") }
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
            throw ApiException(0, "Server Vigilo non raggiungibile: controlla la connessione e che Vigilo sia avviato (componente aggiuntivo di Home Assistant o Docker).")
        }
        if (code == 401) throw ApiException(401, "Abbinamento non valido: genera un nuovo QR nella pagina di configurazione di Vigilo e inquadralo.")
        if (code !in 200..299) {
            val msg = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            val detail = msg?.takeIf { it.isNotBlank() } ?: text.trim().takeIf { code == 409 && it.length <= 200 && it in setOf(
                "vehicle not configured", "vehicle offline or sleeping; no automatic wake",
                "vehicle must report parked gear P; no automatic wake", "vehicle telemetry is not recent; no automatic wake",
            ) }
            val localized = when (detail) {
                "vehicle not configured" -> "Auto non configurata."
                "vehicle offline or sleeping; no automatic wake" -> "Auto offline o addormentata. Risvegliala prima di riprovare."
                "vehicle must report parked gear P; no automatic wake" -> "Posizione P non confermata dal veicolo."
                "vehicle telemetry is not recent; no automatic wake" -> "Telemetria non recente. Attendi dati aggiornati prima di riprovare."
                else -> detail
            }
            throw ApiException(code, localized ?: "Errore del server Vigilo ($code)")
        }
        return json.decodeFromString(text)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        /** True se l'errore indica un server Vigilo senza la funzione richiesta (versione precedente). */
        fun isUnsupported(e: Throwable): Boolean = e is ApiException && e.code == 404

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

        /** Converte lo stato del server nel modello [Snapshot] usato dall'interfaccia. */
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
                trunkOpen = v.trunkOpen,
                frunkOpen = v.frunkOpen,
                insideTemp = v.insideTemp,
                outsideTemp = v.outsideTemp,
                odometerKm = v.odometerKm,
                telemetryConnected = v.telemetryConnected,
            )
        }
    }
}
