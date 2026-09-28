package com.aveharrisan.tgwsproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aveharrisan.tgwsproxy.core.Level
import com.aveharrisan.tgwsproxy.core.Log
import com.aveharrisan.tgwsproxy.core.ProxyServer
import com.aveharrisan.tgwsproxy.core.Stats
import com.aveharrisan.tgwsproxy.core.humanBytesRu
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Служба переднего плана: держит прокси, пока пользователь его не выключит. */
class ProxyService : Service() {
    private var server: ProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var tick: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            stopProxy()
        }
        goForeground(getString(R.string.notif_starting))
        if (server == null) startProxy()
        return START_STICKY
    }

    private fun startProxy() {
        ProxyState.status.value = Status.STARTING
        ProxyState.error.value = null
        val s = Settings.current
        Log.minLevel = if (s.verbose) Level.DEBUG else Level.INFO
        Thread({
            try {
                val cfg = s.toConfig()
                val srv = ProxyServer(cfg)
                srv.start()
                server = srv
                Log.i("Ссылка для Telegram: ${s.localLink()}")
                if (s.wakeLock) acquireLocks()
                ProxyState.startedAt.value = System.currentTimeMillis()
                ProxyState.status.value = Status.RUNNING
                tick = ticker.scheduleWithFixedDelay(::updateNotification, 0, 2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                val msg = when {
                    e is java.net.BindException -> getString(R.string.err_port_busy, s.port)
                    else -> e.message ?: e.toString()
                }
                Log.e("Не удалось запустить прокси: $msg")
                ProxyState.error.value = msg
                ProxyState.status.value = Status.ERROR
                stopSelf()
            }
            ProxyTileService.refresh(this)
        }, "proxy-start").start()
    }

    private fun stopProxy() {
        tick?.cancel(false)
        server?.stop()
        server = null
        releaseLocks()
        if (ProxyState.status.value != Status.ERROR) ProxyState.status.value = Status.STOPPED
        ProxyTileService.refresh(this)
    }

    override fun onDestroy() {
        stopProxy()
        ticker.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title, Settings.current.port))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    @SuppressLint("MissingPermission")
    private var lastNotifText: String? = null

    private fun updateNotification() {
        val text = getString(R.string.notif_stats, Stats.connectionsActive.get(),
            humanBytesRu(Stats.bytesUp.get()), humanBytesRu(Stats.bytesDown.get()))
        if (Experimental.current.quietNotification) {
            // Экран выключен — уведомление никто не видит; цифры те же — перерисовывать нечего.
            val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            if (!screenOn || text == lastNotifText) return
        }
        lastNotifText = text
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text)) }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy::proxy").apply { setReferenceCounted(false); acquire() }
        // Экспериментально: без блокировки Wi-Fi — радиомодуль сам экономит заряд между пакетами.
        if (Experimental.current.wifiPowerSave) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "TgWsProxy::wifi").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null; wifiLock = null
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.aveharrisan.tgwsproxy.STOP"
        private const val ACTION_RESTART = "com.aveharrisan.tgwsproxy.RESTART"

        fun restart(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ProxyService::class.java).setAction(ACTION_RESTART))
        }

        /**
         * false — система не дала запустить службу. На Android 12+ так бывает, когда приложение
         * не на экране (например, нажатие на плитку в шторке на Android 14+ у части прошивок).
         */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ProxyService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException — наследник IllegalStateException.
            Log.w("Система не дала запустить прокси из фона: ${e.javaClass.simpleName}")
            false
        }

        /** stopService разрешён всегда, в отличие от startService из фона. Прокси гасится в onDestroy. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ProxyService::class.java))
        }

        val isActive: Boolean get() = ProxyState.status.value.let { it == Status.RUNNING || it == Status.STARTING }
    }
}
