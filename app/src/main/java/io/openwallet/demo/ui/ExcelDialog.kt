package io.openwallet.demo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.openwallet.demo.domain.Dates
import io.openwallet.demo.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExcelDialog(settingsOverride: ExcelSettings? = null, dismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { settingsOverride ?: ExcelSettings(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = remember(revision) { settings.enabled }
    val tree = remember(revision) { settings.tree }
    val file = remember(revision) { settings.file }
    fun change(action: () -> Unit) {
        scope.launch {
            busy = true; error = null
            try { withContext(Dispatchers.IO) { synchronized(ExcelSync.lock) { action() } } }
            catch (_: Exception) { error = "无法访问文件夹，请重新选择" }
            finally { busy = false }
        }
    }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) change {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            val name = context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':')
            settings.connect(uri.toString(), name)
            ExcelScheduler.restore(context)
            ExcelScheduler.changed(context)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        icon = { Icon(Icons.Rounded.Description, null) },
        title = { Text("Excel 自动导出") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("已入账流水自动保存为 Excel，流水变化后更新同一个文件。")
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(remember(revision) { settings.fileName }, style = MaterialTheme.typography.titleSmall)
                        Text(if (tree.isBlank()) "选择本机文件夹即可开启" else "保存到：${remember(revision) { settings.folderName }}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("excel-choose-folder"), onClick = {
                    try { chooseFolder.launch(tree.takeIf { it.isNotBlank() }?.let {
                        val uri = Uri.parse(it)
                        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    }) }
                    catch (_: ActivityNotFoundException) { error = "未找到系统文件管理器" }
                }) { Text(if (tree.isBlank()) "选择文件夹并开启" else "更换文件夹") }
                Text(remember(revision) { settings.status }, modifier = Modifier.testTag("excel-status"))
                val lastSuccess = remember(revision) { settings.lastSuccess }
                if (lastSuccess > 0) Text("上次成功：${Dates.text(lastSuccess)}", style = MaterialTheme.typography.bodyMedium)
                if (tree.isNotBlank()) {
                    if (enabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { ExcelScheduler.changed(context) }) { Text("立即导出") }
                        TextButton(enabled = !busy, onClick = { change { settings.pause(); ExcelScheduler.pause(context) } }) { Text("暂停导出") }
                    } else OutlinedButton(enabled = !busy, onClick = { change {
                        context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),
                            DocumentsContract.getTreeDocumentId(Uri.parse(tree))), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                            check(it.moveToFirst())
                        } ?: error("missing directory")
                        settings.connect(tree, settings.folderName)
                        ExcelScheduler.restore(context); ExcelScheduler.changed(context)
                    } }) { Text("继续自动导出") }
                }
                if (file.isNotBlank() && lastSuccess > 0) TextButton(enabled = !busy, onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file), ExcelWorkbook.MIME)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: ActivityNotFoundException) { error = "请安装 Excel、WPS 或其他表格应用后打开" }
                }) { Text("打开 Excel 文件") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("excel-error")) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("完成") } },
    )
}
