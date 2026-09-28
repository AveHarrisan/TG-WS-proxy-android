package com.aveharrisan.tgwsproxy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.Updater

/** «Настройки → Обновления»: автопроверка, как часто проверять и когда напомнить после «Позже». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdatesScreen(modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val p by Updater.prefsFlow.collectAsState()
    fun set(v: Updater.Prefs) = Updater.savePrefs(ctx, v)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.updset_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.updset_auto), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(stringResource(if (p.auto) R.string.updset_auto_on else R.string.updset_auto_off),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(p.auto, { set(p.copy(auto = it)) })
            }
        }

        Choice(R.string.updset_every, R.string.updset_every_hint, enabled = p.auto,
            options = listOf(1 to R.string.updset_1h, 6 to R.string.updset_6h, 24 to R.string.updset_24h),
            selected = p.checkEveryHours) { set(p.copy(checkEveryHours = it)) }

        Choice(R.string.updset_remind, R.string.updset_remind_hint, enabled = true,
            options = listOf(1 to R.string.updset_r1h, 24 to R.string.updset_r1d, 72 to R.string.updset_r3d, 168 to R.string.updset_r1w),
            selected = p.remindAfterHours) { set(p.copy(remindAfterHours = it)) }

        Text(stringResource(R.string.updset_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(title: Int, hint: Int, enabled: Boolean, options: List<Pair<Int, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, label) ->
                    FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled,
                        label = { Text(stringResource(label)) })
                }
            }
        }
    }
}
