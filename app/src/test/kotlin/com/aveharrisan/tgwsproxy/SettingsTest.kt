package com.aveharrisan.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().putString("secret", "00000000000000000000000000000000").commit()
    }

    @Test fun freshInstallUsesPort80ForCfAndDisablesH2() {
        Settings.init(ctx)
        val cfg = Settings.current.toConfig()
        assertTrue(Settings.current.noSecure)
        assertTrue(cfg.disableSecure)
        assertFalse(cfg.h2Enabled)
    }

    @Test fun upgradeEnablesPort80ForPreviouslySavedTlsSetting() {
        prefs.edit().putBoolean("noSecure", false).commit()
        Settings.init(ctx)
        assertTrue(Settings.current.noSecure)
        assertTrue(prefs.getBoolean("noSecure", false))
    }

    @Test fun explicitTlsChoiceAfterMigrationSurvivesNextLaunch() {
        Settings.init(ctx)
        Settings.save(Settings.current.copy(noSecure = false))
        Settings.init(ctx)
        assertFalse(Settings.current.noSecure)
        assertTrue(Settings.current.toConfig().h2Enabled)
    }
}
