package io.github.redeyelabs.vigilo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tutte le impostazioni e i token, cifrati con EncryptedSharedPreferences (chiave nel Keystore Android).
 * Nessun segreto è scritto nel codice: il token di abbinamento arriva dal QR del server Vigilo.
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    companion object {

        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(create(context.applicationContext)).also { instance = it }
        }

        private fun create(ctx: Context): SharedPreferences {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                ctx, "vigilo_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String?) = sp.edit { putString(k, v) }

    // --- Backend Home Assistant (componente aggiuntivo Vigilo) ---
    /** Indirizzo pubblico del componente aggiuntivo (https://vigilo.<tailnet>.ts.net). Vuoto = non abbinato. */
    var backendUrl: String get() = str("backend_url"); set(v) = putStr("backend_url", v.trim().trimEnd('/'))
    /** Token di abbinamento letto dal QR. Segreto: resta cifrato sul telefono. */
    var backendToken: String get() = str("backend_token"); set(v) = putStr("backend_token", v.trim())
    /** Ultimo id evento già importato dal componente aggiuntivo. */
    var backendLastEventId: Long get() = sp.getLong("backend_last_event", 0); set(v) = sp.edit { putLong("backend_last_event", v) }
    val backendMode: Boolean get() = backendUrl.isNotBlank() && backendToken.isNotBlank()
    /**
     * Se acceso (solo modalità Home Assistant): l'app interroga gli eventi dell'add-on ogni pochi
     * secondi e mostra una notifica propria per gli allarmi Aware/Panic. Spento di default, per
     * non raddoppiare le notifiche già inviate da Home Assistant.
     */
    var notifyAlarmsFromApp: Boolean get() = sp.getBoolean("notify_app_alarms", false); set(v) = sp.edit { putBoolean("notify_app_alarms", v) }
    /** Se acceso insieme a [notifyAlarmsFromApp], la notifica usa il volume della sveglia (anche in silenzioso). Acceso di default. */
    var alarmBypassSilent: Boolean get() = sp.getBoolean("alarm_bypass_silent", true); set(v) = sp.edit { putBoolean("alarm_bypass_silent", v) }
    fun unpairBackend() = sp.edit { remove("backend_url"); remove("backend_token"); remove("backend_last_event"); remove("last_snapshot") }

    // --- Veicolo ---
    var vin: String get() = str("vin"); set(v) = putStr("vin", v)
    var vehicleName: String get() = str("vehicle_name"); set(v) = putStr("vehicle_name", v)
    /** Nome scelto dall'utente: separato da [vehicleName], che si aggiorna da Tesla. Vuoto = usa quello di Tesla. */
    var vehicleNameCustom: String get() = str("vehicle_name_custom"); set(v) = putStr("vehicle_name_custom", v.trim())
    /** Nome da mostrare: quello scelto dall'utente, altrimenti [teslaName] o quello salvato da Tesla. */
    fun displayVehicleName(teslaName: String? = null): String =
        VehicleName.display(vehicleNameCustom, teslaName, vehicleName)
    /** Cambia a ogni nuova foto scelta dall'utente (per ricaricarla). 0 = foto predefinita. */
    var carPhotoVersion: Long get() = sp.getLong("car_photo_version", 0); set(v) = sp.edit { putLong("car_photo_version", v) }

    var showHeroCar: Boolean get() = sp.getBoolean("show_hero", false); set(v) = sp.edit { putBoolean("show_hero", v) }

    // --- Stato interno per evitare avvisi doppi ---
    var lastSnapshotJson: String get() = str("last_snapshot"); set(v) = putStr("last_snapshot", v)
    /** Istante fino al quale un cambio di Sentinella è atteso (comando inviato da noi). */
    var expectedSentryChangeUntil: Long get() = sp.getLong("exp_sentry", 0); set(v) = sp.edit { putLong("exp_sentry", v) }
    var lastError: String get() = str("last_error"); set(v) = putStr("last_error", v)

    /** Pronta per mostrare l'auto: abbinata a un server Vigilo (componente aggiuntivo o Docker). */
    val isLoggedIn: Boolean get() = backendMode

    /**
     * Dalla 1.3.5 la modalità diretta (login Tesla dall'app) non esiste più: alla prima apertura
     * cancella token, client secret e impostazioni rimaste, una volta sola.
     */
    fun purgeDirectModeOnce() {
        if (sp.getBoolean("direct_mode_purged", false)) return
        sp.edit {
            DIRECT_MODE_KEYS.forEach { remove(it) }
            putBoolean("direct_mode_purged", true)
        }
    }
}

/** Chiavi della vecchia modalità diretta, cancellate all'aggiornamento. */
internal val DIRECT_MODE_KEYS = listOf(
    "client_id", "client_secret", "redirect_uri", "companion_url", "companion_key", "region",
    "access_token", "refresh_token", "access_exp", "pkce_state", "pkce_verifier",
    "proxy_url", "proxy_sha256",
    "polling", "poll_min", "intensive", "intensive_min", "only_sentry", "auto_refresh_sec",
    "auto_flash", "auto_honk", "al_unlocked", "unlocked_min", "al_windows", "al_charge", "al_low", "low_pct", "move_m",
    "tel_jws_url", "tel_auto_renew", "tel_jws_hash", "tel_perm_skip", "tel_jws_exp",
    "unlocked_since", "unlocked_notified", "windows_notified", "low_notified",
    "move_cand_lat", "move_cand_lon", "move_anchor_lat", "move_anchor_lon", "move_cand_n",
)

/** Regola pura per il nome dell'auto (testabile). */
object VehicleName {
    fun display(custom: String, teslaName: String?, savedTeslaName: String): String =
        custom.trim().ifBlank { teslaName?.trim().orEmpty().ifBlank { savedTeslaName.trim() } }
}
