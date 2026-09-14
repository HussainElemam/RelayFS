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

    const val DEFAULT_REDIRECT_URI = "relayfs://oauth"
    private const val PCLOUD_AUTHORIZE_URL = "https://my.pcloud.com/oauth2/authorize"

    private val secureRandom = SecureRandom()
    private val base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()

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
     * Constructs the pCloud authorization URL for OAuth 2.0 with PKCE.
     */
    fun buildAuthorizeUrl(
        clientId: String,
        codeChallenge: String,
        redirectUri: String = DEFAULT_REDIRECT_URI
    ): String {
        val encodedRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        val encodedChallenge = java.net.URLEncoder.encode(codeChallenge, "UTF-8")
        val encodedClientId = java.net.URLEncoder.encode(clientId, "UTF-8")

        return "$PCLOUD_AUTHORIZE_URL?client_id=$encodedClientId&response_type=code&code_challenge=$encodedChallenge&code_challenge_method=S256&redirect_uri=$encodedRedirect"
    }

    /**
     * Launches the authorization flow in a Chrome Custom Tab with fallback to system browser.
     */
    fun launchOAuthFlow(
        context: Context,
        clientId: String,
        codeChallenge: String,
        redirectUri: String = DEFAULT_REDIRECT_URI
    ) {
        val authorizeUrl = buildAuthorizeUrl(clientId, codeChallenge, redirectUri)
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

    /**
     * Base64URL encoding without padding characters.
     */
    fun base64UrlEncode(bytes: ByteArray): String {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

