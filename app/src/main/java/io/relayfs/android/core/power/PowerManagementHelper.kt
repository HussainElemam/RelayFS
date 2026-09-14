package io.relayfs.android.core.power

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper for detecting Android OEM battery optimizations and constructing
 * system intents to request battery optimization exemption for uninterrupted background sync.
 */
object PowerManagementHelper {

    /**
     * Checks whether the application is currently exempted from Android battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return true
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }

    /**
     * Constructs a system intent to directly prompt the user to ignore battery optimizations
     * via [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
     *
     * Requires permission: `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
     */
    fun createRequestIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Constructs a fallback intent opening the system-wide Battery Optimization settings page
     * where the user can manually whitelist RelayFS.
     */
    fun createBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * Convenience check to determine if the user should be prompted to disable battery restrictions.
     */
    fun shouldPromptForBatteryOptimization(context: Context): Boolean {
        return !isIgnoringBatteryOptimizations(context)
    }
}

