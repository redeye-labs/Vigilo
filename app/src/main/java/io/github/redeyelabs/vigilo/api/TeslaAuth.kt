package io.github.redeyelabs.vigilo.api

import android.net.Uri
import android.util.Base64
import io.github.redeyelabs.vigilo.data.Prefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

class AuthException(msg: String) : Exception(msg)

/**
 * OAuth 2.0 authorization-code + PKCE verso auth.tesla.com.
 *
 * Lo scambio del codice (e il refresh) può avvenire in due modi:
 *  1. tramite il companion web (consigliato): il client secret resta sul server (variabile TESLA_CLIENT_SECRET);
 *  2. diretto da telefono: serve il client secret salvato (cifrato) nelle impostazioni.
 * PKCE (code_challenge S256) viene inviato sempre.
 */
object TeslaAuth {
    const val AUTHORIZE_URL = "https://auth.tesla.com/oauth2/v3/authorize"
    const val TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"
    const val SCOPES = "openid offline_access user_data vehicle_device_data vehicle_location vehicle_cmds vehicle_charging_cmds"

    private val json = Json { ignoreUnknownKeys = true }
    private val rnd = SecureRandom()

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { rnd.nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun challenge(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Prepara stato e verifier e restituisce l'URL di login Tesla da aprire nel browser. */
    fun buildAuthorizeUrl(p: Prefs): Uri {
        if (p.clientId.isBlank()) throw AuthException("Inserisci il Client ID nelle impostazioni")
        val state = randomUrlSafe(16)
        val verifier = randomUrlSafe(48)
        p.pendingState = state
        p.pendingVerifier = verifier
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", p.clientId)
            .appendQueryParameter("redirect_uri", p.redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt_missing_scopes", "true")
            .appendQueryParameter("locale", "it-IT")
            .build()
    }

    /** Estrae code/state da un URL di ritorno incollato a mano (o accetta il solo codice). */
    fun parseCallback(text: String): Pair<String, String?> {
        val t = text.trim()
        if (!t.contains("code=")) return t to null
        val uri = Uri.parse(t)
        val code = uri.getQueryParameter("code") ?: throw AuthException("Codice non trovato nell'URL")
        return code to uri.getQueryParameter("state")
    }

    /** Scambia il codice con i token e li salva. */
    fun exchangeCode(p: Prefs, code: String, state: String?) {
        if (state != null && p.pendingState.isNotBlank() && state != p.pendingState)
            throw AuthException("Parametro state non valido: riprova il login")
        val verifier = p.pendingVerifier
        val resp = if (p.companionUrl.isNotBlank()) {
            postCompanion(p, "/api/token", buildJsonObject {
                put("code", code); put("code_verifier", verifier)
                put("redirect_uri", p.redirectUri); put("audience", p.region.baseUrl)
            })
        } else {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", p.clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("audience", p.region.baseUrl)
                .add("redirect_uri", p.redirectUri)
            if (p.clientSecret.isNotBlank()) form.add("client_secret", p.clientSecret)
            postForm(form.build())
        }
        save(p, resp)
        p.pendingState = ""; p.pendingVerifier = ""
    }

    /** Restituisce un access token valido, rinnovandolo se scaduto. */
    @Synchronized
    fun validAccessToken(p: Prefs): String {
        val now = System.currentTimeMillis()
        if (p.accessToken.isNotBlank() && now < p.accessExpiresAt - 60_000) return p.accessToken
        if (p.refreshToken.isBlank()) throw AuthException("Non sei collegato a Tesla: accedi dalle impostazioni")
        val resp = if (p.companionUrl.isNotBlank()) {
            postCompanion(p, "/api/refresh", buildJsonObject { put("refresh_token", p.refreshToken) })
        } else {
            postForm(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", p.clientId)
                    .add("refresh_token", p.refreshToken)
                    .build()
            )
        }
        save(p, resp)
        return p.accessToken
    }

    private fun save(p: Prefs, o: JsonObject) {
        val access = o["access_token"]?.jsonPrimitive?.content
            ?: throw AuthException("Risposta token senza access_token: $o")
        p.accessToken = access
        o["refresh_token"]?.jsonPrimitive?.content?.let { p.refreshToken = it } // il refresh token è monouso: salvo sempre il nuovo
        val exp = o["expires_in"]?.jsonPrimitive?.long ?: 28_800L
        p.accessExpiresAt = System.currentTimeMillis() + exp * 1000
    }

    private fun postForm(body: FormBody): JsonObject {
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        Http.client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AuthException("Errore token Tesla (${r.code}): $txt")
            return json.parseToJsonElement(txt).jsonObject
        }
    }

    private fun postCompanion(p: Prefs, path: String, body: JsonObject): JsonObject {
        val b = Request.Builder().url(p.companionUrl + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (p.companionKey.isNotBlank()) b.header("X-App-Key", p.companionKey)
        Http.slowClient.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AuthException("Errore dal companion web (${r.code}): $txt")
            return json.parseToJsonElement(txt).jsonObject
        }
    }
}
