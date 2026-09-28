package com.aveharrisan.tgwsproxy

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Уведомления об обновлении — когда приложение закрыто и плашку никто не видит. */
object UpdateNotifications {
    const val CHANNEL_ID = "updates"
    const val EXTRA_UPDATE_NOW = "update_now"
    private const val ID_AVAILABLE = 10
    private const val ID_CONFIRM = 11

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.upd_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ctx.getString(R.string.upd_channel_desc)
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun showAvailable(ctx: Context, release: Release) {
        val open = PendingIntent.getActivity(ctx, 20, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val now = PendingIntent.getActivity(ctx, 21,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_UPDATE_NOW, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = release.notes.take(3).joinToString("\n") { "• $it" }.ifEmpty { ctx.getString(R.string.upd_no_notes) }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.upd_title, release.version))
            .setContentText(release.notes.firstOrNull() ?: ctx.getString(R.string.upd_no_notes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.upd_update), now)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_AVAILABLE, n) }
    }

    @SuppressLint("MissingPermission")
    fun showConfirm(ctx: Context, confirm: Intent) {
        val pi = PendingIntent.getActivity(ctx, 22, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.upd_confirm_title))
            .setContentText(ctx.getString(R.string.upd_confirm_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_CONFIRM, n) }
    }

    fun cancelAvailable(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_AVAILABLE)
}
