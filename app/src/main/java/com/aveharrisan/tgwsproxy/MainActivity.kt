package com.aveharrisan.tgwsproxy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aveharrisan.tgwsproxy.ui.AppTheme
import com.aveharrisan.tgwsproxy.ui.InfoScreen
import com.aveharrisan.tgwsproxy.ui.LogsScreen
import com.aveharrisan.tgwsproxy.ui.ProxyScreen
import com.aveharrisan.tgwsproxy.ui.HelpNav
import com.aveharrisan.tgwsproxy.ui.SettingsScreen
import com.aveharrisan.tgwsproxy.ui.UpdateBanner
import com.aveharrisan.tgwsproxy.core.ReleaseNotes
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    val notifGranted = mutableStateOf(true)

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted.value = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Разрешение на уведомления не спрашиваем при запуске: системное окно поверх ещё
        // не нарисованного экрана на части прошивок оставляло серый экран. Спрашиваем при
        // первом «Запустить прокси» и по кнопке «Разрешить» на карточке.
        checkNotif(ask = false)
        RunMarker.restoreIfKilled(applicationContext)
        handleUpdateIntent(intent)
        setContent {
            AppTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val tabs = listOf(
                    Triple(R.string.tab_proxy, Icons.Outlined.PowerSettingsNew, 0),
                    Triple(R.string.tab_settings, Icons.Outlined.Settings, 1),
                    Triple(R.string.tab_logs, Icons.AutoMirrored.Outlined.Subject, 2),
                    Triple(R.string.tab_info, Icons.Outlined.Info, 3),
                )
                Scaffold(bottomBar = {
                    NavigationBar {
                        tabs.forEach { (title, icon, i) ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                icon = { Icon(icon, null) }, label = { Text(stringResource(title)) })
                        }
                    }
                }) { pad ->
                    // Плашка обновления — над любой вкладкой, как сообщение в KotaMusic.
                    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
                        UpdateBanner()
                        val m = Modifier.weight(1f)
                        when (tab) {
                            0 -> ProxyScreen(m, notifGranted.value, onAskNotif = { checkNotif(ask = true) }, onOpenSettings = { tab = 1 },
                                onBeforeStart = { checkNotif(ask = true) })
                            1 -> SettingsScreen(m, onOpenHelp = { topic -> HelpNav.open.value = topic; tab = 3 })
                            2 -> LogsScreen(m)
                            else -> InfoScreen(m)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    /** «Обновить» в уведомлении: открываем приложение и сразу обновляемся, как по кнопке на плашке. */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifications.EXTRA_UPDATE_NOW, false) != true) return
        intent.removeExtra(UpdateNotifications.EXTRA_UPDATE_NOW)
        UpdateNotifications.cancelAvailable(this)
        val release = Updater.cachedRelease(this)?.takeIf { ReleaseNotes.isNewer(it.version, BuildConfig.VERSION_NAME) } ?: return
        if (!Updater.canInstall(this)) { Updater.setStatus(UpdateStatus.Available(release)); Updater.openInstallPermission(this); return }
        lifecycleScope.launch { Updater.update(applicationContext, release) }
    }

    override fun onResume() {
        super.onResume()
        App.inForeground = true
        checkNotif(ask = false)
        // Проверка обновлений при каждом открытии и возвращении в приложение (не чаще раза в 5 минут).
        lifecycleScope.launch { Updater.autoCheck(applicationContext) }
        // Вернулись из настроек с разрешением на установку — продолжаем без лишнего нажатия.
        if (Updater.installAfterPermission && Updater.canInstall(this)) {
            Updater.installAfterPermission = false
            when (val s = Updater.status.value) {
                is UpdateStatus.Ready -> Updater.install(this, s.file)
                is UpdateStatus.Available -> lifecycleScope.launch { Updater.update(applicationContext, s.release) }
                is UpdateStatus.Failed -> s.release?.let { r -> lifecycleScope.launch { Updater.update(applicationContext, r) } }
                else -> {}
            }
        }
    }

    override fun onPause() {
        App.inForeground = false
        super.onPause()
    }

    private fun checkNotif(ask: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notifGranted.value = ok
        if (!ok && ask) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
