package com.aveharrisan.tgwsproxy.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aveharrisan.tgwsproxy.BuildConfig
import com.aveharrisan.tgwsproxy.Links
import com.aveharrisan.tgwsproxy.R
import com.aveharrisan.tgwsproxy.UpdateStatus
import com.aveharrisan.tgwsproxy.Updater
import kotlinx.coroutines.launch

@Composable
fun InfoScreen(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_info), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        AuthorCard()
        UpdateCheckCard()

        SectionTitle(R.string.about_support)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SupportTile(Modifier.weight(1f).fillMaxHeight(), R.drawable.link_boosty, "Boosty", R.string.link_boosty_sub, Links.BOOSTY)
            SupportTile(Modifier.weight(1f).fillMaxHeight(), R.drawable.link_da, "DonationAlerts", R.string.link_da_sub, Links.DONATION_ALERTS)
        }

        SectionTitle(R.string.about_projects)
        LinkGroup {
            LinkRow(R.drawable.link_lvl, "lvl.su", R.string.link_lvl_sub, Links.LVL)
            LinkRow(R.drawable.link_kotamarine, "Котамарин", R.string.link_kotamarine_sub, Links.KOTAMARINE)
            LinkRow(R.drawable.link_kotamusic, "KotaMusic", R.string.link_kotamusic_sub, Links.KOTAMUSIC)
            LinkRow(R.drawable.link_usb, "USB-of_on", R.string.link_usb_sub, Links.USB_OF_ON)
        }

        SectionTitle(R.string.about_contacts)
        LinkGroup {
            LinkRow(R.drawable.link_discord, "Discord", R.string.link_discord_sub, Links.DISCORD)
            LinkRow(R.drawable.link_github, "GitHub", R.string.link_github_sub, Links.REPO)
            ReportRow()
        }

        SectionTitle(R.string.about_help)
        LinkGroup {
            HelpRow(R.string.info_what_title, R.string.info_what)
            HelpRow(R.string.info_how_title, R.string.info_how)
            HelpRow(R.string.info_setup_title, R.string.info_setup)
            HelpRow(R.string.info_trouble_title, R.string.info_trouble)
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(stringResource(R.string.disclaimer), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Автор: аватар, имя и прямые ссылки. */
@Composable
private fun AuthorCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.link_author), null, Modifier.size(64.dp).clip(CircleShape))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("AveHarrisan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.about_author_role), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { openUrl(ctx, Links.AUTHOR_TG) }, Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Telegram")
                }
                FilledTonalButton(onClick = { openUrl(ctx, Links.AUTHOR_GITHUB) }, Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Code, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("GitHub")
                }
            }
        }
    }
}

/** Версия и ручная проверка обновлений. */
@Composable
private fun UpdateCheckCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Updater.status.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleSmall)
                val line = when (val s = status) {
                    UpdateStatus.Checking -> stringResource(R.string.upd_checking)
                    UpdateStatus.UpToDate -> stringResource(R.string.upd_latest)
                    is UpdateStatus.Available -> stringResource(R.string.upd_title, s.release.version)
                    is UpdateStatus.Downloading -> stringResource(R.string.upd_downloading, s.percent)
                    is UpdateStatus.Ready -> stringResource(R.string.upd_ready)
                    is UpdateStatus.Failed -> stringResource(R.string.upd_failed, s.message)
                    UpdateStatus.Idle -> null
                }
                if (line != null) Text(line, style = MaterialTheme.typography.bodySmall,
                    color = if (status is UpdateStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status == UpdateStatus.Checking) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
            else OutlinedButton(onClick = { scope.launch { Updater.check(ctx, manual = true) } }) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.upd_check))
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(stringResource(title), Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SupportTile(modifier: Modifier, @DrawableRes icon: Int, title: String, @StringRes sub: Int, url: String) {
    val ctx = LocalContext.current
    Card(modifier.clip(CardDefaults.shape).clickable { openUrl(ctx, url) }) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(icon), null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LinkGroup(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
private fun LinkRow(@DrawableRes icon: Int, title: String, @StringRes sub: Int, url: String) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { openUrl(ctx, url) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(icon), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(stringResource(sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportRow() {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.BugReport, null, Modifier.size(40.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.rep_problem), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.rep_problem_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) ReportDialog { open = false }
}

/** Пункт справки: заголовок кнопкой, текст раскрывается по нажатию. */
@Composable
private fun HelpRow(@StringRes title: Int, @StringRes body: Int) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        AnimatedVisibility(open) {
            Column {
                Text(stringResource(body), Modifier.padding(start = 54.dp, end = 16.dp, bottom = 14.dp), style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
            }
        }
    }
}
