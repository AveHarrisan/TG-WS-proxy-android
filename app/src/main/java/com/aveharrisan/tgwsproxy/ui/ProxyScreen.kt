package com.aveharrisan.tgwsproxy.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.ProxyService
import com.aveharrisan.tgwsproxy.ProxyState
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.Settings
import com.aveharrisan.tgwsproxy.Status
import com.aveharrisan.tgwsproxy.TileAdder
import com.aveharrisan.tgwsproxy.core.Diagnostics
import com.aveharrisan.tgwsproxy.core.Stats
import com.aveharrisan.tgwsproxy.core.humanBytesRu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProxyScreen(
    modifier: Modifier,
    notifGranted: Boolean,
    onAskNotif: () -> Unit,
    onOpenSettings: () -> Unit,
    onBeforeStart: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val status by ProxyState.status.collectAsState()
    val error by ProxyState.error.collectAsState()
    val settings by Settings.flow.collectAsState()
    val link = settings.localLink()
    var diagOpen by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    LaunchedEffect(Unit) { while (true) { batteryOk = isIgnoringBattery(ctx); delay(2000) } }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.by_author), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }

        StatusCard(status, error, settings.port)

        val running = status == Status.RUNNING || status == Status.STARTING
        Button(
            onClick = {
                if (running) ProxyService.stop(ctx)
                else {
                    // Прокси работает и без разрешения, просто Android может выгрузить его из памяти.
                    if (!notifGranted) onBeforeStart()
                    ProxyService.start(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.buttonColors(),
        ) {
            Icon(Icons.Outlined.PowerSettingsNew, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (running) R.string.btn_stop else R.string.btn_start), style = MaterialTheme.typography.titleMedium)
        }

        FilledTonalButton(
            onClick = { openInTelegram(ctx, link) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = link.isNotEmpty(),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_apply_tg))
        }

        if (!notifGranted) WarnCard(Icons.Outlined.NotificationsOff, stringResource(R.string.warn_notif), stringResource(R.string.btn_allow), onAskNotif)
        if (!batteryOk) WarnCard(Icons.Outlined.BatteryAlert, stringResource(R.string.warn_battery), stringResource(R.string.btn_allow)) {
            requestIgnoreBattery(ctx)
        }
        TileCard()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.connection_data), style = MaterialTheme.typography.titleSmall)
                KeyValue(stringResource(R.string.label_server), "127.0.0.1")
                KeyValue(stringResource(R.string.label_port), settings.port.toString())
                KeyValue(stringResource(R.string.label_secret), link.substringAfter("secret=", ""))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(link, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { copy(ctx, link) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
                }
            }
        }

        if (status == Status.RUNNING) StatsCard()

        OutlinedButton(onClick = { diagOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.NetworkCheck, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_diag))
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.go_settings))
        }
    }

    if (diagOpen) DiagnosticsDialog { diagOpen = false }
}

@Composable
private fun StatusCard(status: Status, error: String?, port: Int) {
    val (color, title) = when (status) {
        Status.RUNNING -> Color(0xFF2E9E5B) to stringResource(R.string.status_running, port)
        Status.STARTING -> Color(0xFFE0A100) to stringResource(R.string.status_starting)
        Status.ERROR -> MaterialTheme.colorScheme.error to stringResource(R.string.status_error)
        Status.STOPPED -> MaterialTheme.colorScheme.outline to stringResource(R.string.status_stopped)
    }
    val c by animateColorAsState(color, label = "status")
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(14.dp), shape = CircleShape, color = c) {}
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (status == Status.ERROR && error != null)
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (status == Status.STOPPED)
                    Text(stringResource(R.string.status_stopped_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatsCard() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val started by ProxyState.startedAt.collectAsState()
    val up = ((now - started) / 1000).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.stats), style = MaterialTheme.typography.titleSmall)
            KeyValue(stringResource(R.string.stat_uptime), "%d:%02d:%02d".format(up / 3600, up / 60 % 60, up % 60))
            KeyValue(stringResource(R.string.stat_active), "${Stats.connectionsActive.get()} / ${Stats.connectionsTotal.get()}")
            KeyValue(stringResource(R.string.stat_routes),
                "WS ${Stats.connectionsWs.get()} · CF ${Stats.connectionsCfProxy.get()} · TCP ${Stats.connectionsTcpFallback.get()}")
            KeyValue(stringResource(R.string.stat_traffic), "↑ ${humanBytesRu(Stats.bytesUp.get())}   ↓ ${humanBytesRu(Stats.bytesDown.get())}")
            if (Stats.connectionsBad.get() > 0) KeyValue(stringResource(R.string.stat_bad), Stats.connectionsBad.get().toString())
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun WarnCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

/** Предложение вынести кнопку прокси в шторку. Пропадает, когда плитка добавлена или карточку скрыли. */
@Composable
private fun TileCard() {
    val ctx = LocalContext.current
    val added by TileAdder.added.collectAsState()
    val prefs = remember { ctx.getSharedPreferences("tile", Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getBoolean("cardHidden", false)) }
    var manual by remember { mutableStateOf(false) }
    if (added || hidden) return

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ToggleOn, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.tile_card), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { hidden = true; prefs.edit().putBoolean("cardHidden", true).apply() }) {
                    Text(stringResource(R.string.tile_hide))
                }
                TextButton(onClick = { addTile(ctx) { manual = true } }) { Text(stringResource(R.string.tile_add)) }
            }
        }
    }
    if (manual) TileManualDialog { manual = false }
}

/** Системное окно «Добавить плитку» (Android 13+), а на старых — подсказка, как добавить руками. */
fun addTile(ctx: Context, onManual: () -> Unit) {
    if (!TileAdder.canRequest) { onManual(); return }
    TileAdder.request(ctx) { ok -> if (ok) Toast.makeText(ctx, R.string.tile_added, Toast.LENGTH_SHORT).show() }
}

@Composable
fun TileManualDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.tile_manual_title)) },
        text = { Text(stringResource(R.string.tile_manual_text)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun DiagnosticsDialog(onClose: () -> Unit) {
    val results = remember { mutableStateListOf<Diagnostics.Result>() }
    var running by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val cfg = Settings.current.toConfigOrNull()
        if (cfg == null) { failed = "Проверьте настройки"; running = false; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            Diagnostics.run(cfg) { r -> launch(Dispatchers.Main) { results += r } }
        }
        running = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.btn_diag)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.diag_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                results.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(10.dp), shape = CircleShape,
                            color = if (r.ok) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error) {}
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${r.method} · ${r.target}", style = MaterialTheme.typography.bodyMedium)
                            Text(if (r.ok) "${r.ms} мс" else r.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (running) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        },
    )
}

fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TG WS Proxy", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
}

private fun openInTelegram(ctx: Context, link: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        copy(ctx, link)
        Toast.makeText(ctx, R.string.no_telegram, Toast.LENGTH_LONG).show()
    }
}

private fun isIgnoringBattery(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBattery(ctx: Context) {
    val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    try { ctx.startActivity(direct) } catch (e: Exception) {
        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
