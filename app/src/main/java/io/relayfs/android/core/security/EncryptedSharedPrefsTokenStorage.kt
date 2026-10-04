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

    override suspend fun saveSession(accountId: String, token: String, endpoint: String, identity: String) = withContext(ioDispatcher) {
        check(sharedPreferences.edit().putString(keyForAccount(accountId), token).putString(keyForEndpoint(accountId), endpoint)
            .putString("identity_$accountId", identity).commit()) { "Could not persist cloud session" }
    }

    override suspend fun saveIdentity(accountId: String, identity: String) = withContext(ioDispatcher) {
        check(sharedPreferences.edit().putString("identity_$accountId", identity).commit()) { "Could not save account identity" }
    }
    override suspend fun getIdentity(accountId: String): String? = withContext(ioDispatcher) {
        readString("identity_$accountId")
    }

    override suspend fun saveToken(accountId: String, token: String) = withContext(ioDispatcher) {
        sharedPreferences.edit()
            .putString(keyForAccount(accountId), token)
            .commit().also { check(it) { "Could not persist credentials" } }
        Unit
    }

    override suspend fun getToken(accountId: String): String? = withContext(ioDispatcher) {
        readString(keyForAccount(accountId))
    }

    override suspend fun deleteToken(accountId: String) = withContext(ioDispatcher) {
        sharedPreferences.edit()
            .remove(keyForAccount(accountId))
            .remove(keyForEndpoint(accountId))
            .commit().also { check(it) { "Could not persist credentials" } }
        Unit
    }

    override suspend fun hasToken(accountId: String): Boolean = withContext(ioDispatcher) {
        readString(keyForAccount(accountId)) != null
    }

    override suspend fun saveEndpoint(accountId: String, endpoint: String) = withContext(ioDispatcher) {
        sharedPreferences.edit()
            .putString(keyForEndpoint(accountId), endpoint)
            .commit().also { check(it) { "Could not persist credentials" } }
        Unit
    }

    override suspend fun getEndpoint(accountId: String): String? = withContext(ioDispatcher) {
        readString(keyForEndpoint(accountId))
    }

    private fun readString(key: String): String? = try { sharedPreferences.getString(key, null) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null } // Corrupt entries require reauthentication; encrypted bytes stay intact.

    companion object {
        fun create(context: Context): TokenStorage = try { EncryptedSharedPrefsTokenStorage(context) }
        catch (e: java.security.GeneralSecurityException) { UnavailableTokenStorage() }
        catch (e: java.io.IOException) { UnavailableTokenStorage() }
        catch (e: SecurityException) { UnavailableTokenStorage() }
        catch (e: IllegalStateException) { UnavailableTokenStorage() }
        catch (e: IllegalArgumentException) { UnavailableTokenStorage() }

        const val DEFAULT_PREF_FILE = "relayfs_secure_tokens"
        private const val KEY_PREFIX = "token_account_"
        private const val KEY_ENDPOINT_PREFIX = "endpoint_account_"

        fun keyForAccount(accountId: String): String = "$KEY_PREFIX$accountId"
        fun keyForEndpoint(accountId: String): String = "$KEY_ENDPOINT_PREFIX$accountId"

        fun createEncryptedPreferences(
            context: Context,
            prefFileName: String = DEFAULT_PREF_FILE
        ): SharedPreferences {
            val selector = context.getSharedPreferences("relayfs_credential_store", Context.MODE_PRIVATE)
            val selected = selector.getString("store", prefFileName) ?: prefFileName
            fun open(name: String): SharedPreferences {
                val alias = if (name == prefFileName) MasterKey.DEFAULT_MASTER_KEY_ALIAS else "relayfs_$name"
                val key = MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(context, name, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            }
            return recoverPreferences({ open(selected) }) {
                val fresh = "relayfs_secure_recovery_${java.util.UUID.randomUUID()}"
                val prefs = open(fresh)
                check(selector.edit().putString("store", fresh).commit())
                prefs
            }
        }

        fun recoverPreferences(open: () -> SharedPreferences, recover: () -> SharedPreferences): SharedPreferences = try {
            open()
        } catch (e: java.security.GeneralSecurityException) { recover() }
        catch (e: java.io.IOException) { recover() }
        catch (e: SecurityException) { recover() }
        catch (e: IllegalArgumentException) { recover() }
    }
}

/** Fail closed if the Keystore is unavailable; never fall back to plaintext credentials. */
private class UnavailableTokenStorage : TokenStorage {
    override suspend fun saveToken(accountId: String, token: String) { throw java.io.IOException("Secure credential storage is unavailable. Reconnect after unlocking or repairing device security.") }
    override suspend fun getToken(accountId: String): String? = null
    override suspend fun deleteToken(accountId: String) {}
    override suspend fun hasToken(accountId: String) = false
    override suspend fun saveIdentity(accountId: String, identity: String) { throw java.io.IOException("Secure credential storage is unavailable") }
}
