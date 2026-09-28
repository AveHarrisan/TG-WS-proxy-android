package com.aveharrisan.tgwsproxy.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.Links
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.core.Diagnostics
import com.aveharrisan.tgwsproxy.core.Proto
import androidx.compose.ui.text.font.FontFamily

/** Как завести свой Cloudflare Worker: шаги и кнопки. Общий для «Справки» и настроек. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkerGuideContent(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.worker_why), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.worker_caveat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.worker_steps), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.worker_blocked), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { copyWorkerCode(ctx) }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.worker_copy))
            }
            OutlinedButton(onClick = { openUrl(ctx, Links.CLOUDFLARE_DASH) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.worker_open_cf))
            }
            OutlinedButton(onClick = { openUrl(ctx, Links.WORKER_GUIDE) }) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.worker_full_guide))
            }
        }
    }
}

/** Какой пункт «Справки» открыть по значку «?» в настройках. */
enum class HelpTopic { WORKER, CF_DOMAIN }

object HelpNav {
    val open = kotlinx.coroutines.flow.MutableStateFlow<HelpTopic?>(null)
}

/** Свой домен CF-прокси: шаги и кнопки. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CfDomainGuideContent(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.cfdom_why), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.cfdom_steps), style = MaterialTheme.typography.bodyMedium)
        Text(cfDnsRecords(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.cfdom_steps2), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                copy(ctx, cfDnsRecords())
                Toast.makeText(ctx, R.string.cfdom_copied, Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cfdom_copy))
            }
            OutlinedButton(onClick = { openUrl(ctx, Links.CLOUDFLARE_DASH) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.worker_open_cf))
            }
            OutlinedButton(onClick = { openUrl(ctx, Links.CF_DOMAIN_GUIDE) }) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.worker_full_guide))
            }
        }
    }
}

/** DNS-записи A для своего домена — из того же списка адресов, что использует прокси. */
fun cfDnsRecords(): String = Diagnostics.CF_DOMAIN_DCS.joinToString("\n") { dc ->
    "kws$dc".padEnd(8) + "A  " + Proto.DC_DEFAULT_IPS.getValue(dc)
}

/** Код Worker лежит в приложении (res/raw/cf_worker.js) — вставить в редактор Cloudflare. */
fun copyWorkerCode(ctx: Context) {
    val code = ctx.resources.openRawResource(R.raw.cf_worker).bufferedReader().use { it.readText() }
    copy(ctx, code)
    Toast.makeText(ctx, R.string.worker_copied, Toast.LENGTH_SHORT).show()
}
