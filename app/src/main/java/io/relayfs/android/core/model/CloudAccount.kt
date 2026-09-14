package io.relayfs.android.core.model

/**
 * Represents a user account connected to a cloud provider.
 */
data class CloudAccount(
    val id: String, // UUID
    val providerType: ProviderType, // PCLOUD, WEBDAV
    val email: String,
    val displayName: String,
    val serverUrl: String, // e.g., "https://api.pcloud.com"
    val connectedAt: Long = System.currentTimeMillis()
)

enum class ProviderType {
    PCLOUD,
    WEBDAV
}

