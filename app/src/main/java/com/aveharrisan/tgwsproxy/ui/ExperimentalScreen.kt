package com.aveharrisan.tgwsproxy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.Experimental
import com.aveharrisan.tgwsproxy.R

/** Отдельный экран из «Настроек»: экономия заряда. Переключатели применяются сразу, без «Сохранить». */
@Composable
fun ExperimentalScreen(modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val f by Experimental.flow.collectAsState()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.exp_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.padding(16.dp)) {
                Icon(Icons.Outlined.Science, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.exp_intro), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }

        ExpSwitch(R.string.exp_pool, R.string.exp_pool_hint, f.poolSleep) { Experimental.set(f.copy(poolSleep = it)) }
        ExpSwitch(R.string.exp_notif, R.string.exp_notif_hint, f.quietNotification) { Experimental.set(f.copy(quietNotification = it)) }
        ExpSwitch(R.string.exp_wifi, R.string.exp_wifi_hint, f.wifiPowerSave) { Experimental.set(f.copy(wifiPowerSave = it)) }

        Text(stringResource(R.string.exp_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpSwitch(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked, onChange)
        }
    }
}
