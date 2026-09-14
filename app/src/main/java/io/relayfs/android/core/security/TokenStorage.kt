package io.relayfs.android.core.security

/**
 * Secure storage interface for sensitive cloud provider access and refresh tokens.
 * Implementations must use hardware-backed encryption (e.g. Android Keystore).
 */
interface TokenStorage {

    /**
     * Securely stores an access or refresh token for a cloud account.
     */
    suspend fun saveToken(accountId: String, token: String)

    /**
     * Retrieves the stored token for a cloud account, or null if none exists.
     */
    suspend fun getToken(accountId: String): String?

    /**
     * Removes the stored token for a cloud account.
     */
    suspend fun deleteToken(accountId: String)

    /**
     * Checks if a token exists for a cloud account.
     */
    suspend fun hasToken(accountId: String): Boolean
}

