package com.aveharrisan.tgwsproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Xiaomi/Redmi/POCO (MIUI, HyperOS) выгружают фоновые приложения без отдельного разрешения
 * «Автозапуск», даже когда экономия батареи для них отключена.
 */
object Autostart {
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it == "xiaomi" || it == "redmi" || it == "poco" }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autostart", Context.MODE_PRIVATE)
    fun isDone(ctx: Context) = prefs(ctx).getBoolean("done", false)
    fun markDone(ctx: Context) = prefs(ctx).edit().putBoolean("done", true).apply()

    /** Раздел автозапуска в «Безопасности» MIUI/HyperOS; нет его — обычная страница приложения. */
    fun open(ctx: Context) {
        val miui = Intent().setComponent(ComponentName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val details = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(miui) }.onFailure { runCatching { ctx.startActivity(details) } }
    }
}
