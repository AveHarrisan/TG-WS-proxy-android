package com.aveharrisan.tgwsproxy.ui

import androidx.compose.foundation.layout.size
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import com.aveharrisan.tgwsproxy.core.Diagnostics
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import com.aveharrisan.tgwsproxy.Updater
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Science
import androidx.compose.runtime.saveable.rememberSaveable
import com.aveharrisan.tgwsproxy.Experimental
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.AppSettings
import com.aveharrisan.tgwsproxy.ProxyService
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.Settings
import com.aveharrisan.tgwsproxy.TileAdder
import com.aveharrisan.tgwsproxy.core.DcIpParser
import com.aveharrisan.tgwsproxy.core.Domains
import com.aveharrisan.tgwsproxy.core.Level
import com.aveharrisan.tgwsproxy.core.Log
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(modifier: Modifier, onOpenHelp: (HelpTopic) -> Unit = {}) {
    var experimentalOpen by rememberSaveable { mutableStateOf(false) }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    // Экспериментальный режим — поверх формы: форма остаётся на месте вместе с несохранёнными правками.
    Box(modifier) {
        SettingsForm(Modifier.fillMaxSize(), onOpenExperimental = { experimentalOpen = true }, onOpenUpdates = { updatesOpen = true },
            onOpenHelp = onOpenHelp)
        if (updatesOpen) Surface(Modifier.fillMaxSize()) {
            UpdatesScreen(Modifier) { updatesOpen = false }
        }
        if (experimentalOpen) Surface(Modifier.fillMaxSize()) {
            ExperimentalScreen(Modifier) { experimentalOpen = false }
        }
    }
}

@Composable
private fun SettingsForm(modifier: Modifier, onOpenExperimental: () -> Unit, onOpenUpdates: () -> Unit, onOpenHelp: (HelpTopic) -> Unit) {
    val ctx = LocalContext.current
    val saved by Settings.flow.collectAsState()
    var s by remember { mutableStateOf(saved) }
    var portText by remember { mutableStateOf(saved.port.toString()) }
    var poolText by remember { mutableStateOf(saved.poolSize.toString()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var savedNote by remember { mutableStateOf(false) }
    LaunchedEffect(savedNote) { if (savedNote) { delay(2500); savedNote = false } }

    val portErr = portText.toIntOrNull()?.takeIf { it in 1024..65535 } == null
    val poolErr = poolText.toIntOrNull()?.takeIf { it in 0..16 } == null
    val secretErr = !(s.secret.length == 32 && s.secret.all { it in '0'..'9' || it in 'a'..'f' })
    val dcErr = runCatching { DcIpParser.parse(s.dcIps.lines()) }.exceptionOrNull()?.message
    val cfErr = Domains.coerceList(s.cfDomains).firstOrNull { !Domains.isValid(it.lowercase()) }
    val workerErr = Domains.coerceList(s.cfWorkerDomains).firstOrNull { !Domains.isValid(it.lowercase()) }
    val tlsErr = s.fakeTlsDomain.isNotBlank() && !Domains.isValid(s.fakeTlsDomain.trim().lowercase())

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall)

        Section(stringResource(R.string.sec_main))
        OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.label_port)) }, singleLine = true, isError = portErr,
            supportingText = { Text(stringResource(if (portErr) R.string.err_port else R.string.hint_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(s.secret, { s = s.copy(secret = it.lowercase().filter { c -> c in '0'..'9' || c in 'a'..'f' }.take(32)) },
            Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_secret)) }, singleLine = true,
            isError = secretErr, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            supportingText = { Text(stringResource(if (secretErr) R.string.err_secret else R.string.hint_secret)) },
            trailingIcon = { IconButton(onClick = { s = s.copy(secret = Settings.newSecret()) }) { Icon(Icons.Outlined.Refresh, null) } })

        Section(stringResource(R.string.sec_dc))
        OutlinedTextField(s.dcIps, { s = s.copy(dcIps = it) }, Modifier.fillMaxWidth(), minLines = 2,
            label = { Text(stringResource(R.string.label_dc_ips)) }, isError = dcErr != null,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            supportingText = { Text(dcErr ?: stringResource(R.string.hint_dc_ips)) })
        OutlinedButton(onClick = { s = s.copy(dcIps = AppSettings().dcIps) }) { Text(stringResource(R.string.btn_reset_dc)) }

        Section(stringResource(R.string.sec_cf))
        SwitchRow(stringResource(R.string.label_cf), stringResource(R.string.hint_cf), s.cfProxy) { s = s.copy(cfProxy = it) }
        OutlinedTextField(s.cfDomains, { s = s.copy(cfDomains = it) }, Modifier.fillMaxWidth(), enabled = s.cfProxy,
            label = { Text(stringResource(R.string.label_cf_domains)) }, isError = cfErr != null,
            trailingIcon = { IconButton(onClick = { onOpenHelp(HelpTopic.CF_DOMAIN) }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.cfdom_title)) } },
            supportingText = { Text(if (cfErr != null) stringResource(R.string.err_domain, cfErr) else stringResource(R.string.hint_cf_domains)) })
        CfDomainCheck(s.cfDomains, secure = !s.noSecure, enabled = cfErr == null)
        OutlinedTextField(s.cfWorkerDomains, { s = s.copy(cfWorkerDomains = it) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.label_worker)) }, isError = workerErr != null,
            // «?» — в «Справку»: как завести свой Worker.
            trailingIcon = { IconButton(onClick = { onOpenHelp(HelpTopic.WORKER) }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.worker_how)) } },
            supportingText = { Text(if (workerErr != null) stringResource(R.string.err_domain, workerErr) else stringResource(R.string.hint_worker)) })
        WorkerCheck(s.cfWorkerDomains, secure = !s.noSecure, enabled = workerErr == null)
        SwitchRow(stringResource(R.string.label_nosecure), stringResource(R.string.hint_nosecure), s.noSecure) { s = s.copy(noSecure = it) }

        Section(stringResource(R.string.sec_advanced))
        OutlinedTextField(poolText, { poolText = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.label_pool)) }, singleLine = true, isError = poolErr,
            supportingText = { Text(stringResource(if (poolErr) R.string.err_pool else R.string.hint_pool)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        SwitchRow(stringResource(R.string.label_lan), stringResource(R.string.hint_lan), s.allowLan) { s = s.copy(allowLan = it) }
        OutlinedTextField(s.fakeTlsDomain, { s = s.copy(fakeTlsDomain = it.trim()) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.label_faketls)) }, isError = tlsErr,
            supportingText = { Text(stringResource(if (tlsErr) R.string.err_faketls else R.string.hint_faketls)) })

        Section(stringResource(R.string.sec_behavior))
        SwitchRow(stringResource(R.string.label_autostart), stringResource(R.string.hint_autostart), s.autostart) { s = s.copy(autostart = it) }
        SwitchRow(stringResource(R.string.label_wakelock), stringResource(R.string.hint_wakelock), s.wakeLock) { s = s.copy(wakeLock = it) }
        SwitchRow(stringResource(R.string.label_show_notif), stringResource(if (s.showNotification) R.string.hint_show_notif_on else R.string.hint_show_notif_off),
            s.showNotification) { s = s.copy(showNotification = it) }
        SwitchRow(stringResource(R.string.label_verbose), stringResource(R.string.hint_verbose), s.verbose) { s = s.copy(verbose = it) }
        TileRow()
        UpdatesRow(onOpenUpdates)
        ExperimentalRow(onOpenExperimental)

        HorizontalDivider()
        // Сводка ошибок — прямо у кнопки, чтобы не искать, что не так.
        if (errors.isNotEmpty()) Column {
            Text(stringResource(R.string.err_summary), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
            errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (savedNote) Text(stringResource(R.string.saved), color = MaterialTheme.colorScheme.primary)
        Row {
            OutlinedButton(onClick = {
                s = saved; portText = saved.port.toString(); poolText = saved.poolSize.toString(); errors = emptyList()
            }, Modifier.weight(1f)) { Text(stringResource(R.string.btn_cancel)) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                val errs = buildList {
                    if (portErr) add(ctx.getString(R.string.err_port))
                    if (secretErr) add(ctx.getString(R.string.err_secret))
                    if (dcErr != null) add(dcErr)
                    if (cfErr != null) add(ctx.getString(R.string.err_domain, cfErr))
                    if (workerErr != null) add(ctx.getString(R.string.err_domain, workerErr))
                    if (poolErr) add(ctx.getString(R.string.err_pool))
                    if (tlsErr) add(ctx.getString(R.string.err_faketls))
                }
                errors = errs
                if (errs.isNotEmpty()) return@Button
                val next = s.copy(port = portText.toInt(), poolSize = poolText.toInt())
                val changed = next != saved
                Settings.save(next)
                s = next
                Log.minLevel = if (next.verbose) Level.DEBUG else Level.INFO
                if (changed && ProxyService.isActive) ProxyService.restart(ctx)
                savedNote = true
            }, Modifier.weight(1f)) { Text(stringResource(R.string.btn_save)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

/** «Проверить домен» под полем своих доменов: по каждому датацентру — отвечает ли запись kws<DC>. */
@Composable
private fun CfDomainCheck(input: String, secure: Boolean, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val domains = remember(input) { Domains.coerceList(input) }
    var running by remember { mutableStateOf(false) }
    var results by remember(input) { mutableStateOf<List<Pair<String, List<Diagnostics.Result>>>>(emptyList()) }
    if (domains.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                running = true
                results = emptyList()
                scope.launch {
                    results = withContext(Dispatchers.IO) {
                        domains.map { d -> async { d to Diagnostics.probeCfDomain(d, secure) } }.awaitAll()
                    }
                    running = false
                }
            }, enabled = enabled && !running) { Text(stringResource(R.string.cfdom_check)) }
            if (running) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        results.forEach { (d, rs) ->
            val bad = rs.filter { !it.ok }
            if (bad.isEmpty()) Text(stringResource(R.string.cfdom_ok, d, rs.size), style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E9E5B))
            else {
                Text(stringResource(R.string.cfdom_bad, d, bad.joinToString(", ") { "kws" + it.method.removePrefix("DC") }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(cfDomainReason(bad.first().detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Почему запись своего домена не отвечает — по коду ответа Cloudflare. */
@Composable
private fun cfDomainReason(detail: String): String {
    val known = when {
        detail.contains("UnknownHost", true) || detail.contains("Unable to resolve", true) -> R.string.cfdom_err_dns
        detail.contains("503") -> R.string.cfdom_err_busy
        Regex("\\b52[5-6]\\b").containsMatchIn(detail) -> R.string.cfdom_err_ssl
        Regex("\\b52[0-4]\\b").containsMatchIn(detail) -> R.string.cfdom_err_origin
        detail.contains("Сертификат") || detail.contains("SSL", true) -> R.string.cfdom_err_cert
        detail.contains("timed out", true) || detail.contains("timeout", true) -> R.string.cfdom_err_timeout
        else -> null
    }
    return if (known != null) stringResource(known) else stringResource(R.string.worker_err_other) + " ($detail)"
}

/** «Проверить» под полем Worker: проверяет то, что введено, ещё до «Сохранить». */
@Composable
private fun WorkerCheck(input: String, secure: Boolean, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val domains = remember(input) { Domains.coerceList(input) }
    var running by remember { mutableStateOf(false) }
    var results by remember(input) { mutableStateOf<List<Pair<String, Diagnostics.Result>>>(emptyList()) }
    if (domains.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                running = true
                results = emptyList()
                scope.launch {
                    results = withContext(Dispatchers.IO) {
                        domains.map { d -> async { d to Diagnostics.probeWorker(d, secure) } }.awaitAll()
                    }
                    running = false
                }
            }, enabled = enabled && !running) { Text(stringResource(R.string.worker_check)) }
            if (running) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        results.forEach { (d, r) ->
            Text(
                if (r.ok) stringResource(R.string.worker_ok, d, r.ms) else stringResource(R.string.worker_fail, d, workerReason(r.detail)),
                style = MaterialTheme.typography.bodySmall,
                color = if (r.ok) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Почему Worker не ответил — человеческими словами; подробности — только если причина не распознана. */
@Composable
private fun workerReason(detail: String): String {
    val known = when {
        detail.contains("404") -> R.string.worker_err_404
        detail.contains("426") || detail.contains("Expected websocket", true) -> R.string.worker_err_code
        detail.contains("1101") || detail.contains("500") || detail.contains("502") -> R.string.worker_err_code
        detail.contains("timed out", true) || detail.contains("timeout", true) -> R.string.worker_err_timeout
        detail.contains("UnknownHost", true) || detail.contains("Unable to resolve", true) -> R.string.worker_err_dns
        detail.contains("Сертификат") || detail.contains("SSL", true) -> R.string.worker_err_tls
        else -> null
    }
    return if (known != null) stringResource(known)
    else stringResource(R.string.worker_err_other) + if (detail.isNotBlank()) " ($detail)" else ""
}

@Composable
private fun UpdatesRow(onClick: () -> Unit) {
    val p = Updater.prefsFlow.collectAsState().value
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.updset_title), style = MaterialTheme.typography.bodyLarge)
            Text(if (p.auto) stringResource(R.string.updset_row_on, p.checkEveryHours) else stringResource(R.string.updset_row_off),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExperimentalRow(onClick: () -> Unit) {
    val on = Experimental.flow.collectAsState().value.any
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Science, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.exp_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(if (on) R.string.exp_row_on else R.string.exp_row_off), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TileRow() {
    val ctx = LocalContext.current
    val added by TileAdder.added.collectAsState()
    var manual by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.label_tile), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(if (added) R.string.hint_tile_added else R.string.hint_tile_add), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = { addTile(ctx) { manual = true } }) { Text(stringResource(R.string.tile_add)) }
    }
    if (manual) TileManualDialog { manual = false }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}
