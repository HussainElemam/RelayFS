package io.relayfs.android.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TokenStorage] implementation backed by AndroidX Security Crypto's
 * [EncryptedSharedPreferences] and the Android Keystore.
 */
class EncryptedSharedPrefsTokenStorage(
    private val sharedPreferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TokenStorage {

    /**
     * Primary convenience constructor for Android runtime using MasterKey and EncryptedSharedPreferences.
     */
    constructor(
        context: Context,
        prefFileName: String = DEFAULT_PREF_FILE,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        sharedPreferences = createEncryptedPreferences(context, prefFileName),
        ioDispatcher = ioDispatcher
    )

    override suspend fun saveToken(accountId: String, token: String) = withContext(ioDispatcher) {
        sharedPreferences.edit()
            .putString(keyForAccount(accountId), token)
            .apply()
    }

    override suspend fun getToken(accountId: String): String? = withContext(ioDispatcher) {
        sharedPreferences.getString(keyForAccount(accountId), null)
    }

    override suspend fun deleteToken(accountId: String) = withContext(ioDispatcher) {
        sharedPreferences.edit()
            .remove(keyForAccount(accountId))
            .remove(keyForEndpoint(accountId))
            .apply()
    }

    override suspend fun hasToken(accountId: String): Boolean = withContext(ioDispatcher) {
        sharedPreferences.contains(keyForAccount(accountId))
    }

    override suspend fun saveEndpoint(accountId: String, endpoint: String) = withContext(ioDispatcher) {
        sharedPreferences.edit()
            .putString(keyForEndpoint(accountId), endpoint)
            .apply()
    }

    override suspend fun getEndpoint(accountId: String): String? = withContext(ioDispatcher) {
        sharedPreferences.getString(keyForEndpoint(accountId), null)
    }

    companion object {
        const val DEFAULT_PREF_FILE = "relayfs_secure_tokens"
        private const val KEY_PREFIX = "token_account_"
        private const val KEY_ENDPOINT_PREFIX = "endpoint_account_"

        fun keyForAccount(accountId: String): String = "$KEY_PREFIX$accountId"
        fun keyForEndpoint(accountId: String): String = "$KEY_ENDPOINT_PREFIX$accountId"

        fun createEncryptedPreferences(
            context: Context,
            prefFileName: String = DEFAULT_PREF_FILE
        ): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                prefFileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}

