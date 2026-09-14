package io.relayfs.android.core.provider.pcloud.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Injects OAuth 2.0 Bearer authorization header into outgoing HTTP requests.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenProvider()

        val request = if (!token.isNullOrBlank() && originalRequest.header("Authorization") == null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(request)
    }
}

/**
 * Handles HTTP 429 Too Many Requests with exponential backoff retries.
 */
class RateLimitInterceptor(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 30000L
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempt = 0

        while (response.code == 429 && attempt < maxRetries) {
            attempt++
            response.close()

            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
            val delayMs = if (retryAfterSeconds != null) {
                (retryAfterSeconds * 1000L).coerceIn(initialDelayMs, maxDelayMs)
            } else {
                (initialDelayMs * (1 shl (attempt - 1))).coerceIn(initialDelayMs, maxDelayMs)
            }

            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Rate limit retry interrupted", e)
            }

            response = chain.proceed(chain.request())
        }

        return response
    }
}

/**
 * Masks sensitive authorization headers and tokens from logs.
 */
object TokenRedactingHelper {
    fun redactUrl(url: String): String {
        return url.replace(Regex("(access_token=)[^&]+"), "$1[REDACTED]")
    }

    fun redactHeader(name: String, value: String): String {
        return if (name.equals("Authorization", ignoreCase = true) && value.startsWith("Bearer ", ignoreCase = true)) {
            "Bearer [REDACTED]"
        } else {
            value
        }
    }
}

