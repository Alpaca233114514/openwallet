package io.openwallet.demo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import io.openwallet.demo.update.*

@Composable
fun SettingsScreen(
    onImport: () -> Unit,
    onExport: () -> Unit,
    onCapture: () -> Unit,
    onExcel: () -> Unit,
    updates: UpdateViewModel = viewModel(),
) {
    val result by updates.result.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var linkError by remember { mutableStateOf<String?>(null) }
    fun openPage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            linkError = null
        } catch (_: ActivityNotFoundException) {
            linkError = "未找到可用的浏览器，请安装浏览器后重试"
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("OpenWallet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("版本 ${updates.versionName}（${updates.versionCode}）", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            SettingsGroup("账本与数据") {
                SettingsRow(Icons.Rounded.FileDownload, "导入银行流水", onImport)
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingsRow(Icons.Rounded.FileUpload, "导出 CSV", onExport)
            }
        }
        item {
            SettingsGroup("自动记账与导出") {
                SettingsRow(Icons.Rounded.NotificationsNone, "通知自动记账", onCapture)
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingsRow(Icons.Rounded.Description, "Excel 自动导出", onExcel)
            }
        }
        item {
            SettingsGroup("应用更新") {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("从 GitHub Releases 获取最新公开版本", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (val status = result) {
                        UpdateResult.Idle -> Unit
                        UpdateResult.Checking -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在检查更新…")
                        }
                        UpdateResult.UpToDate -> Text("当前已是最新版本", color = MaterialTheme.colorScheme.primary)
                        UpdateResult.NoPublicRelease -> Text("暂未找到公开发布版本")
                        is UpdateResult.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                        is UpdateResult.Available -> {
                            Text("发现新版本 ${status.release.version}", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            if (status.release.notes.isNotBlank()) {
                                Text("更新说明", fontWeight = FontWeight.SemiBold)
                                Text(status.release.notes, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (!status.release.hasApk) Text("该版本尚未上传 APK 安装包")
                            Button(onClick = { openPage(status.release.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (status.release.hasApk) "前往下载更新" else "查看新版本")
                            }
                        }
                    }
                    Button(onClick = { linkError = null; updates.check() }, enabled = result != UpdateResult.Checking,
                        modifier = Modifier.fillMaxWidth().testTag("check-updates")) {
                        Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (result == UpdateResult.Checking) "检查中…" else if (result is UpdateResult.Failed) "重新检查" else "检查更新")
                    }
                    TextButton(onClick = { openPage(UpdateSource.RELEASES_URL) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("查看所有版本")
                    }
                    linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, action: () -> Unit) {
    Surface(onClick = action, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
