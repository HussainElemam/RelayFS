package io.relayfs.android.core.provider

import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.security.TokenStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudProviderFactoryTest {

    @Test
    fun normalCredentialCreatesPCloudProvider() = runTest {
        val storage = FakeTokenStorage(mapOf("pcloud_default" to "pcloud-token"))

        val provider = DefaultCloudProviderFactory(storage).getProvider("pcloud_default")

        assertNotNull(provider)
        assertEquals(ProviderType.PCLOUD, provider?.providerType)
    }

    @Test
    fun legacyWebDavAccountIdIsDormantInV1() = runTest {
        val storage = FakeTokenStorage(mapOf("webdav-account" to "legacy-token"))

        val provider = DefaultCloudProviderFactory(storage).getProvider("webdav-account")

        assertNull(provider)
    }

    @Test
    fun legacyWebDavTokenIsDormantInV1() = runTest {
        val storage = FakeTokenStorage(mapOf("legacy-account" to "webdav:https://example.test|user|pass"))

        val provider = DefaultCloudProviderFactory(storage).getProvider("legacy-account")

        assertNull(provider)
    }

    private class FakeTokenStorage(initialTokens: Map<String, String>) : TokenStorage {
        private val tokens = initialTokens.toMutableMap()

        override suspend fun getIdentity(accountId: String) = "https://api.pcloud.com#123"

        override suspend fun saveToken(accountId: String, token: String) {
            tokens[accountId] = token
        }

        override suspend fun getToken(accountId: String): String? = tokens[accountId]

        override suspend fun deleteToken(accountId: String) {
            tokens.remove(accountId)
        }

        override suspend fun hasToken(accountId: String): Boolean = tokens.containsKey(accountId)
    }
}
