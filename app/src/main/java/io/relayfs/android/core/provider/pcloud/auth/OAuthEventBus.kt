package io.relayfs.android.core.provider.pcloud.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Result data payload parsed from pCloud's redirect callback URI.
 */
data class OAuthCallbackData(
    val accessToken: String? = null,
    val authCode: String? = null,
    val locationId: Int = 1,
    val errorMessage: String? = null // 1 = US (api.pcloud.com), 2 = EU (eapi.pcloud.com)
) {
    constructor(authCode: String, locationId: Int) : this(accessToken = null, authCode = authCode, locationId = locationId)
}

/**
 * Lightweight reactive event bus for delivering OAuth authorization callbacks to listening components.
 */
object OAuthEventBus {

    private val _events = MutableSharedFlow<OAuthCallbackData>(extraBufferCapacity = 1)
    val events: SharedFlow<OAuthCallbackData> = _events.asSharedFlow()

    fun emitCallback(data: OAuthCallbackData) {
        _events.tryEmit(data)
    }
}

