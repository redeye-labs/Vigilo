package io.github.redeyelabs.vigilo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.redeyelabs.vigilo.api.Http

/** Regioni Fleet API. L'Italia usa la regione EU. */
enum class Region(val label: String, val baseUrl: String) {
    EU("Europa / Medio Oriente / Africa", "https://fleet-api.prd.eu.vn.cloud.tesla.com"),
    NA("Nord America / Asia-Pacifico", "https://fleet-api.prd.na.vn.cloud.tesla.com"),
}

/**
 * Tutte le impostazioni e i token, cifrati con EncryptedSharedPreferences (chiave nel Keystore Android).
 * Nessun segreto è scritto nel codice: client id, eventuale client secret e token li inserisce l'utente.
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

    // --- Credenziali applicazione Tesla ---
    var clientId: String get() = str("client_id"); set(v) = putStr("client_id", v.trim())
    /** Facoltativo: solo se NON si usa il companion web per lo scambio del codice. */
    var clientSecret: String get() = str("client_secret"); set(v) = putStr("client_secret", v.trim())
    var redirectUri: String get() = str("redirect_uri"); set(v) = putStr("redirect_uri", v.trim())
    /** URL facoltativo di un companion web che conserva il client secret lato server. Vuoto = scambio diretto. */
    var companionUrl: String get() = str("companion_url"); set(v) = putStr("companion_url", v.trim().trimEnd('/'))
    /** Chiave condivisa opzionale verso il companion (header X-App-Key). */
    var companionKey: String get() = str("companion_key"); set(v) = putStr("companion_key", v.trim())
    var region: Region
        get() = runCatching { Region.valueOf(str("region", Region.EU.name)) }.getOrDefault(Region.EU)
        set(v) = putStr("region", v.name)

    // --- Backend Home Assistant (componente aggiuntivo Vigilo) ---
    /** Indirizzo pubblico del componente aggiuntivo (https://vigilo.<tailnet>.ts.net). Vuoto = modalità diretta. */
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

    // --- Proxy comandi firmati (tesla-http-proxy) ---
    var proxyUrl: String
        get() = str("proxy_url")
        set(v) {
            val n = Http.normalizeProxyUrl(v)
            val old = str("proxy_url")
            if (n != old) {
                putStr("proxy_url", n)
                // URL cambiato: la vecchia impronta non è più valida (trust on first use).
                putStr("proxy_sha256", "")
                Http.invalidatePinnedClient()
            }
        }
    /** Impronta SHA-256 (hex) del certificato del proxy, per certificati autofirmati. */
    var proxyCertSha256: String
        get() = str("proxy_sha256")
        set(v) {
            val n = Http.normalizeFingerprint(v)
            putStr("proxy_sha256", n)
            Http.invalidatePinnedClient()
        }

    // --- Token ---
    var accessToken: String get() = str("access_token"); set(v) = putStr("access_token", v)
    var refreshToken: String get() = str("refresh_token"); set(v) = putStr("refresh_token", v)
    var accessExpiresAt: Long get() = sp.getLong("access_exp", 0); set(v) = sp.edit { putLong("access_exp", v) }
    var pendingState: String get() = str("pkce_state"); set(v) = putStr("pkce_state", v)
    var pendingVerifier: String get() = str("pkce_verifier"); set(v) = putStr("pkce_verifier", v)

    // --- Veicolo ---
    var vin: String get() = str("vin"); set(v) = putStr("vin", v)
    var vehicleName: String get() = str("vehicle_name"); set(v) = putStr("vehicle_name", v)

    // --- Monitoraggio ---
    var pollingEnabled: Boolean get() = sp.getBoolean("polling", true); set(v) = sp.edit { putBoolean("polling", v) }
    /** Intervallo del lavoro periodico (minimo 15 min imposto da Android/WorkManager). */
    var pollMinutes: Int get() = sp.getInt("poll_min", 15); set(v) = sp.edit { putInt("poll_min", v.coerceAtLeast(15)) }
    var intensiveEnabled: Boolean get() = sp.getBoolean("intensive", false); set(v) = sp.edit { putBoolean("intensive", v) }
    /** Intervallo del monitoraggio in primo piano (1..30 min). */
    var intensiveMinutes: Int get() = sp.getInt("intensive_min", 3); set(v) = sp.edit { putInt("intensive_min", v.coerceIn(1, 30)) }
    var onlyWhenSentry: Boolean get() = sp.getBoolean("only_sentry", true); set(v) = sp.edit { putBoolean("only_sentry", v) }
    /**
     * Aggiornamento automatico della schermata Auto, in secondi (0 = spento, predefinito).
     * Valori ammessi: 0, 30, 60, 120.
     */
    var autoRefreshSeconds: Int
        get() {
            val v = sp.getInt("auto_refresh_sec", 0)
            return if (v in listOf(0, 30, 60, 120)) v else 0
        }
        set(v) = sp.edit { putInt("auto_refresh_sec", if (v in listOf(0, 30, 60, 120)) v else 0) }
    var showHeroCar: Boolean get() = sp.getBoolean("show_hero", false); set(v) = sp.edit { putBoolean("show_hero", v) }

    // --- Reazioni ed avvisi ---
    var autoFlash: Boolean get() = sp.getBoolean("auto_flash", false); set(v) = sp.edit { putBoolean("auto_flash", v) }
    var autoHonk: Boolean get() = sp.getBoolean("auto_honk", false); set(v) = sp.edit { putBoolean("auto_honk", v) }
    var alertUnlocked: Boolean get() = sp.getBoolean("al_unlocked", true); set(v) = sp.edit { putBoolean("al_unlocked", v) }
    var unlockedMinutes: Int get() = sp.getInt("unlocked_min", 10); set(v) = sp.edit { putInt("unlocked_min", v.coerceIn(1, 240)) }
    var alertWindows: Boolean get() = sp.getBoolean("al_windows", true); set(v) = sp.edit { putBoolean("al_windows", v) }
    var alertChargeComplete: Boolean get() = sp.getBoolean("al_charge", true); set(v) = sp.edit { putBoolean("al_charge", v) }
    var alertLowBattery: Boolean get() = sp.getBoolean("al_low", true); set(v) = sp.edit { putBoolean("al_low", v) }
    var lowBatteryPct: Int get() = sp.getInt("low_pct", 20); set(v) = sp.edit { putInt("low_pct", v.coerceIn(5, 80)) }
    var movementMeters: Int get() = sp.getInt("move_m", 50); set(v) = sp.edit { putInt("move_m", v.coerceIn(20, 1000)) }


    // --- Avvisi Sentinella (Fleet Telemetry) ---
    /** URL del JWS firmato su Home Assistant. Vuoto = derivato dall'host del proxy (http, porta 80). */
    var telemetryJwsUrl: String get() = str("tel_jws_url"); set(v) = putStr("tel_jws_url", v.trim())
    /** Rinnovo automatico quotidiano (in carica o su Wi-Fi). Acceso di default. */
    var telemetryAutoRenew: Boolean get() = sp.getBoolean("tel_auto_renew", true); set(v) = sp.edit { putBoolean("tel_auto_renew", v) }
    /** Hash SHA-256 del JWS inviato con successo (per rilevare un cambio). */
    var lastEnrolledJwsHash: String get() = str("tel_jws_hash"); set(v) = putStr("tel_jws_hash", v)
    /** True se l'ultimo invio dello stesso JWS è stato saltato per un motivo permanente. */
    var lastEnrollPermanentSkip: Boolean get() = sp.getBoolean("tel_perm_skip", false); set(v) = sp.edit { putBoolean("tel_perm_skip", v) }
    /** Scadenza (unix seconds) decodificata dal payload JWS "exp". 0 = sconosciuta. */
    var telemetryJwsExp: Long get() = sp.getLong("tel_jws_exp", 0); set(v) = sp.edit { putLong("tel_jws_exp", v) }

    // --- Stato interno per evitare avvisi doppi ---
    var lastSnapshotJson: String get() = str("last_snapshot"); set(v) = putStr("last_snapshot", v)
    var unlockedSince: Long get() = sp.getLong("unlocked_since", 0); set(v) = sp.edit { putLong("unlocked_since", v) }
    var unlockedNotified: Boolean get() = sp.getBoolean("unlocked_notified", false); set(v) = sp.edit { putBoolean("unlocked_notified", v) }
    var windowsNotified: Boolean get() = sp.getBoolean("windows_notified", false); set(v) = sp.edit { putBoolean("windows_notified", v) }
    var lowNotified: Boolean get() = sp.getBoolean("low_notified", false); set(v) = sp.edit { putBoolean("low_notified", v) }
    /** Istante fino al quale un cambio di Sentinella è atteso (comando inviato da noi). */
    var expectedSentryChangeUntil: Long get() = sp.getLong("exp_sentry", 0); set(v) = sp.edit { putLong("exp_sentry", v) }
    /** Posizione "sospetta" in attesa di conferma (evita falsi allarmi per salti del GPS). */
    var moveCandidateLat: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("move_cand_lat", 0)); set(v) = sp.edit { putLong("move_cand_lat", v.toRawBits()) }
    var moveCandidateLon: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("move_cand_lon", 0)); set(v) = sp.edit { putLong("move_cand_lon", v.toRawBits()) }
    var moveAnchorLat: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("move_anchor_lat", 0)); set(v) = sp.edit { putLong("move_anchor_lat", v.toRawBits()) }
    var moveAnchorLon: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("move_anchor_lon", 0)); set(v) = sp.edit { putLong("move_anchor_lon", v.toRawBits()) }
    var moveCandidateCount: Int get() = sp.getInt("move_cand_n", 0); set(v) = sp.edit { putInt("move_cand_n", v) }
    var lastError: String get() = str("last_error"); set(v) = putStr("last_error", v)

    /** Pronta per mostrare l'auto: abbinata a Home Assistant oppure login diretto con veicolo scelto. */
    val isLoggedIn: Boolean get() = backendMode || refreshToken.isNotBlank() || accessToken.isNotBlank()
    val directLoggedIn: Boolean get() = refreshToken.isNotBlank() || accessToken.isNotBlank()

    fun logout() = sp.edit {
        remove("access_token"); remove("refresh_token"); remove("access_exp"); remove("last_snapshot")
    }
}
