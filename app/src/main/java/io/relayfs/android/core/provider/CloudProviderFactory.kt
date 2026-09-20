package io.relayfs.android.core.provider

import io.relayfs.android.core.provider.pcloud.PCloudProvider
import io.relayfs.android.core.provider.pcloud.api.PCloudApiClient
import io.relayfs.android.core.security.TokenStorage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Factory for resolving and initializing [CloudProvider] instances for given cloud accounts.
 */
interface CloudProviderFactory {

    /**
     * Obtains an initialized [CloudProvider] for the specified account ID,
     * or null if no valid credentials or provider configuration exists.
     */
    suspend fun getProvider(accountId: String): CloudProvider?
}

/**
 * Default implementation of [CloudProviderFactory] that uses [TokenStorage]
 * and initializes the native pCloud provider used by RelayFS v1.
 */
class DefaultCloudProviderFactory(
    private val tokenStorage: TokenStorage,
    private val baseUrl: String = PCloudApiClient.US_API_URL,
    private val customOkHttpClient: OkHttpClient? = null
) : CloudProviderFactory {

    override suspend fun getProvider(accountId: String): CloudProvider? {
        val token = tokenStorage.getToken(accountId) ?: return null

        // WebDAV credentials from prototype builds remain intentionally dormant until v2.
        // Do not reinterpret them as pCloud bearer tokens.
        if (accountId.startsWith("webdav", ignoreCase = true) || token.startsWith("webdav:", ignoreCase = true)) {
            return null
        }

        val effectiveEndpoint = tokenStorage.getEndpoint(accountId) ?: baseUrl
        val apiClient = PCloudApiClient(
            baseUrl = effectiveEndpoint,
            tokenProvider = {
                runBlocking { tokenStorage.getToken(accountId) }
            },
            customOkHttpClient = customOkHttpClient
        )
        return PCloudProvider(accountId = accountId, apiClient = apiClient)
    }

}
