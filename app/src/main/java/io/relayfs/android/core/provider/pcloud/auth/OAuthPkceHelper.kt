package io.relayfs.android.core.provider.pcloud.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Utility implementing RFC 7636 Proof Key for Code Exchange (PKCE) for OAuth 2.0.
 */
object OAuthPkceHelper {

    const val DEFAULT_REDIRECT_URI = "pcloud-oauth://io.relayfs.android"
    private const val PCLOUD_AUTHORIZE_URL = "https://my.pcloud.com/oauth2/authorize"
    private const val STATE_PREFS = "pcloud_oauth_state"
    private const val KEY_PENDING_STATE = "pending_state"
    private const val KEY_PENDING_STATE_CREATED_AT = "pending_state_created_at"
    private const val STATE_TTL_MILLIS = 10 * 60 * 1000L

    private val secureRandom = SecureRandom()
    private val base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()
    private val stateLock = Any()

    /**
     * Generates a high-entropy cryptographic code verifier (RFC 7636 section 4.1).
     *
     * @param length Number of characters between 43 and 128 (default: 64).
     */
    fun generateCodeVerifier(length: Int = 64): String {
        require(length in 43..128) { "code_verifier length must be between 43 and 128 characters" }
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = base64UrlAlphabet[secureRandom.nextInt(base64UrlAlphabet.size)]
        }
        return String(chars)
    }

    /** Generates a single-use OAuth state value used to bind a callback to this app session. */
    fun generateState(length: Int = 64): String = generateCodeVerifier(length)

    /**
     * Computes the S256 code challenge for the given code verifier (RFC 7636 section 4.2).
     * code_challenge = BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))
     */
    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncode(hashBytes)
    }

    /**
     * Constructs the pCloud authorization URL for OAuth 2.0.
     *
     * @param responseType Either "token" (implicit Token flow for mobile) or "code" (PKCE Code flow).
     */
    fun buildAuthorizeUrl(
        clientId: String,
        codeChallenge: String? = null,
        redirectUri: String = DEFAULT_REDIRECT_URI,
        responseType: String = if (codeChallenge != null) "code" else "token",
        state: String? = null
    ): String {
        val encodedRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        val encodedClientId = java.net.URLEncoder.encode(clientId, "UTF-8")

        val sb = StringBuilder("$PCLOUD_AUTHORIZE_URL?client_id=$encodedClientId&response_type=$responseType&redirect_uri=$encodedRedirect")
        if (responseType == "code" && !codeChallenge.isNullOrBlank()) {
            val encodedChallenge = java.net.URLEncoder.encode(codeChallenge, "UTF-8")
            sb.append("&code_challenge=$encodedChallenge&code_challenge_method=S256")
        }
        if (!state.isNullOrBlank()) {
            sb.append("&state=${java.net.URLEncoder.encode(state, "UTF-8")}")
        }
        return sb.toString()
    }

    /**
     * Launches the authorization flow in a Chrome Custom Tab with fallback to system browser.
     */
    fun launchOAuthFlow(
        context: Context,
        clientId: String,
        codeChallenge: String? = null,
        redirectUri: String = DEFAULT_REDIRECT_URI,
        responseType: String = if (codeChallenge != null) "code" else "token"
    ) {
        val state = generateState()
        savePendingState(context, state)
        val authorizeUrl = buildAuthorizeUrl(
            clientId = clientId,
            codeChallenge = codeChallenge,
            redirectUri = redirectUri,
            responseType = responseType,
            state = state
        )
        val uri = Uri.parse(authorizeUrl)

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(context, uri)
        } catch (_: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        }
    }

    fun savePendingState(context: Context, state: String) {
        require(state.isNotBlank()) { "OAuth state must not be blank" }
        synchronized(stateLock) {
            val saved = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_STATE, state)
                .putLong(KEY_PENDING_STATE_CREATED_AT, System.currentTimeMillis())
                .commit()
            check(saved) { "Could not persist OAuth state" }
        }
    }

    /** Validates and consumes a matching, non-expired OAuth state exactly once. */
    fun validateAndConsumeState(context: Context, returnedState: String?): Boolean {
        if (returnedState.isNullOrBlank()) return false
        synchronized(stateLock) {
            val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            val expectedState = prefs.getString(KEY_PENDING_STATE, null) ?: return false
            val createdAt = prefs.getLong(KEY_PENDING_STATE_CREATED_AT, 0L)
            val age = System.currentTimeMillis() - createdAt
            if (createdAt <= 0L || age < 0L || age > STATE_TTL_MILLIS) {
                prefs.edit().clear().apply()
                return false
            }

            val matches = MessageDigest.isEqual(
                expectedState.toByteArray(Charsets.UTF_8),
                returnedState.toByteArray(Charsets.UTF_8)
            )
            if (matches) {
                prefs.edit().clear().apply()
            }
            return matches
        }
    }

    fun isExpectedRedirectUri(uri: Uri): Boolean {
        val expected = Uri.parse(DEFAULT_REDIRECT_URI)
        return uri.scheme == expected.scheme &&
            uri.host == expected.host &&
            uri.port == expected.port &&
            uri.path.orEmpty() == expected.path.orEmpty()
    }

    /**
     * Parses parameters from an OAuth redirect callback URI, inspecting both the URI fragment
     * (used by pCloud's Token flow: redirect_uri#access_token=...&locationid=...) and the query string
     * (used by Code flow: redirect_uri?code=...&locationid=...).
     */
    fun parseCallbackUri(uri: Uri): Map<String, String> {
        val params = mutableMapOf<String, String>()

        val fragment = uri.fragment
        if (!fragment.isNullOrBlank()) {
            fragment.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = try {
                        java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } catch (_: Exception) {
                        parts[1]
                    }
                    params[key] = value
                }
            }
        }

        try {
            for (name in uri.queryParameterNames) {
                uri.getQueryParameter(name)?.let { value ->
                    params.putIfAbsent(name, value)
                }
            }
        } catch (_: Exception) {
            // Ignore opaque URI parse failures
        }

        return params
    }

    /**
     * Base64URL encoding without padding characters.
     */
    fun base64UrlEncode(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }
}
