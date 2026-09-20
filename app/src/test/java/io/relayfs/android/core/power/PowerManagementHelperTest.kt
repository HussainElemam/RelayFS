package io.relayfs.android.core.power

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerManagementHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testIsIgnoringBatteryOptimizationsReturnsBoolean() {
        val isIgnoring = PowerManagementHelper.isIgnoringBatteryOptimizations(context)
        // In Robolectric default state, should return boolean without crashing
        assertNotNull(isIgnoring)
    }

    @Test
    fun testCreateBatteryOptimizationSettingsIntent() {
        val intent = PowerManagementHelper.createBatteryOptimizationSettingsIntent()

        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
    }

    @Test
    fun testShouldPromptConsistency() {
        val isIgnoring = PowerManagementHelper.isIgnoringBatteryOptimizations(context)
        val shouldPrompt = PowerManagementHelper.shouldPromptForBatteryOptimization(context)

        assertEquals(!isIgnoring, shouldPrompt)
    }
}
