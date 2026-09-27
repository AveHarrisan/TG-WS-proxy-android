package com.aveharrisan.tgwsproxy.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.BuildConfig
import com.aveharrisan.tgwsproxy.R

@Composable
fun InfoScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_info), style = MaterialTheme.typography.headlineSmall)
        InfoCard(R.string.info_what_title, R.string.info_what)
        InfoCard(R.string.info_how_title, R.string.info_how)
        InfoCard(R.string.info_setup_title, R.string.info_setup)
        InfoCard(R.string.info_trouble_title, R.string.info_trouble)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(stringResource(R.string.disclaimer), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { open("https://github.com/AveHarrisan") }) { Text(stringResource(R.string.author)) }
        TextButton(onClick = { open("https://github.com/Flowseal/tg-ws-proxy") }) { Text(stringResource(R.string.credits)) }
    }
}

@Composable
private fun InfoCard(title: Int, body: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
