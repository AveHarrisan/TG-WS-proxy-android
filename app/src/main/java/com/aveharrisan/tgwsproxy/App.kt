package com.aveharrisan.tgwsproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aveharrisan.tgwsproxy.core.Log
import javax.net.ssl.HttpsURLConnection

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogFile.init(this)
        Settings.init(this)
        Experimental.init(this)
        Updater.loadPrefs(this)
        Updater.onAppStart(this)
        UpdateNotifications.createChannel(this)
        TileAdder.init(this)
        ProxyState.attachLog()
        // На Android системный верификатор имён — полноценный, подключаем его вторым рубежом.
        com.aveharrisan.tgwsproxy.core.Net.hostnameVerifier = { host, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        }
        Log.minLevel = if (Settings.current.verbose) com.aveharrisan.tgwsproxy.core.Level.DEBUG else com.aveharrisan.tgwsproxy.core.Level.INFO
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_desc)
                    setShowBadge(false)
                }
            )
            // Тихий канал: без значка в строке состояния, уведомление свёрнуто внизу шторки.
            // Совсем без уведомления Android не даёт держать прокси в фоне.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, getString(R.string.channel_silent_name), NotificationManager.IMPORTANCE_MIN).apply {
                    description = getString(R.string.channel_silent_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "proxy"
        const val CHANNEL_SILENT = "proxy_silent"

        /** Приложение на экране: окно подтверждения установки можно показать сразу, а не уведомлением. */
        @Volatile var inForeground = false
    }
}
