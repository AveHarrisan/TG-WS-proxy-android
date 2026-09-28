package com.aveharrisan.tgwsproxy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aveharrisan.tgwsproxy.ui.AppTheme
import com.aveharrisan.tgwsproxy.ui.ExperimentalScreen
import com.aveharrisan.tgwsproxy.ui.InfoScreen
import com.aveharrisan.tgwsproxy.ui.SettingsScreen
import com.aveharrisan.tgwsproxy.ui.UpdatesScreen
import com.aveharrisan.tgwsproxy.ui.ProxyScreen
import com.aveharrisan.tgwsproxy.ui.UpdateBanner
import androidx.compose.ui.test.hasText
import com.aveharrisan.tgwsproxy.ui.ReportDialog
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Снимки экранов без телефона: PNG в app/build/screens — смотреть глазами. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File("build/screens/$name.png"))

    private val release = Release(
        "1.2.0",
        listOf(
            "Обновления прямо в приложении: плашка, список изменений и установка поверх",
            "Кнопку прокси можно добавить в шторку одним нажатием",
            "Сбор логов и отправка задачи на GitHub",
            "Новая вкладка «О программе»",
        ),
        "https://example.invalid/app.apk", 1_500_000, "https://example.invalid",
    )

    private fun screen(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        AppTheme { Surface(Modifier.fillMaxSize()) { Column { UpdateBanner(); content() } } }
    }

    @Test fun proxy() { Updater.setStatus(UpdateStatus.Idle); screen { ProxyScreen(Modifier, true, {}, {}) }; shot("proxy") }

    @Test fun updateAvailable() { Updater.setStatus(UpdateStatus.Available(release)); screen { ProxyScreen(Modifier, true, {}, {}) }; shot("update_available") }

    @Test fun updateDownloading() { Updater.setStatus(UpdateStatus.Downloading(release, 42)); screen { }; shot("update_downloading") }

    @Test fun updateFailed() {
        Updater.setStatus(UpdateStatus.Failed("нет связи с GitHub — проверьте интернет", release)); screen { }; shot("update_failed")
    }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun about() { Updater.setStatus(UpdateStatus.UpToDate); screen { InfoScreen(Modifier) }; shot("about") }

    @Test @Config(qualifiers = "w411dp-h2600dp-night-xxhdpi")
    fun aboutDark() { Updater.setStatus(UpdateStatus.Idle); screen { InfoScreen(Modifier) }; shot("about_dark") }

    @OptIn(ExperimentalRoborazziApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun report() {
        Updater.setStatus(UpdateStatus.Idle)
        screen { ReportDialog {} }
        compose.waitUntilAtLeastOneExists(hasText("Задача на GitHub"), timeoutMillis = 60_000)
        captureScreenRoboImage("build/screens/report.png")
    }

    @Test fun experimental() {
        Updater.setStatus(UpdateStatus.Idle)
        Experimental.set(ExperimentalFlags(poolSleep = true))
        screen { ExperimentalScreen(Modifier) {} }
        shot("experimental")
        Experimental.set(ExperimentalFlags())
    }

    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun settings() { Updater.setStatus(UpdateStatus.Idle); screen { SettingsScreen(Modifier) }; shot("settings") }

    @Test fun updatesSettings() { Updater.setStatus(UpdateStatus.Idle); screen { UpdatesScreen(Modifier) {} }; shot("updates_settings") }
}
