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
        Settings.init(this)
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
        }
    }

    companion object {
        const val CHANNEL_ID = "proxy"
    }
}
