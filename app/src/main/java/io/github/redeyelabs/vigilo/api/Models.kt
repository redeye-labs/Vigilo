package io.github.redeyelabs.vigilo.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ApiException(val code: Int, msg: String) : Exception(msg)

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
