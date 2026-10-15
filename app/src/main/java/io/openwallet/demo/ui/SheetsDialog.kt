package io.openwallet.demo.ui

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import io.openwallet.demo.domain.Dates
import io.openwallet.demo.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SheetsDialog(settingsOverride: SheetsSettings? = null, dismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { settingsOverride ?: SheetsSettings(context) }
    val client = remember { Identity.getAuthorizationClient(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val target = remember(revision) { settings.target() }
    var email by rememberSaveable { mutableStateOf(target.email) }
    var requestedEmail by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val finishAuthorization: (AuthorizationResult) -> Unit = { result ->
        val token = result.accessToken
        if (token.isNullOrBlank() || result.hasResolution() || requestedEmail.isBlank()) {
            busy = false; error = "授权未完成，请重试"
        } else {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        synchronized(SheetsSync.lock) {
                            val gateway = GoogleSheetsGateway(context, settings.installKey)
                            gateway.verifyAccount(token, requestedEmail)
                            settings.connect(requestedEmail)
                            SheetsScheduler.restore(context)
                            SheetsScheduler.changed(context)
                        }
                    }
                    error = null
                } catch (e: ExportFailure) { error = e.message }
                catch (_: Exception) { error = "连接失败，请检查网络和 Google Cloud OAuth 配置" }
                finally { busy = false }
            }
        }
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) { busy = false; error = "已取消授权" }
        else try { finishAuthorization(client.getAuthorizationResultFromIntent(result.data)) }
        catch (_: Exception) { busy = false; error = "授权失败，请检查 Google Cloud OAuth 配置后重试" }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Google Sheets 自动导出") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = email, onValueChange = { email = it; error = null }, label = { Text("目标 Google 邮箱") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("sheets-email"))
            Text("已入账流水将自动更新到此账户的专用表格。", style = MaterialTheme.typography.bodyMedium)
            if (target.email.isNotBlank()) Text("当前账户：${target.email}")
            Button(enabled = !busy && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(), modifier = Modifier.fillMaxWidth(), onClick = {
                requestedEmail = email.trim().lowercase(java.util.Locale.ROOT)
                busy = true; error = null
                client.authorize(SheetsAuthorization.request(requestedEmail)).addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        val intent = result.pendingIntent
                        if (intent == null) { busy = false; error = "无法打开 Google 授权" }
                        else try { authorization.launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
                        catch (_: Exception) { busy = false; error = "无法打开 Google 授权，请检查 Google Play 服务" }
                    } else finishAuthorization(result)
                }.addOnFailureListener { busy = false; error = "授权失败，请检查 Google Play 服务与 OAuth 配置" }
            }) { Text(if (target.email.isBlank()) "连接并开启" else "授权并启用") }
            Text(remember(revision) { settings.status }, modifier = Modifier.testTag("sheets-status"))
            val lastSuccess = remember(revision) { settings.lastSuccess }
            if (lastSuccess > 0) Text("上次成功：${Dates.text(lastSuccess)}", style = MaterialTheme.typography.bodyMedium)
            if (target.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && !target.needsAuthorization, onClick = {
                        SheetsScheduler.changed(context); error = "已安排导出，联网后执行"
                    }) { Text("立即导出") }
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try { withContext(Dispatchers.IO) { synchronized(SheetsSync.lock) { settings.pause(); SheetsScheduler.pause(context) } }; error = null }
                            finally { busy = false }
                        }
                    }) { Text("暂停导出") }
                }
            }
            val file = remember(revision) { settings.file() }
            if (file != null) TextButton(enabled = !busy, onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.google.com/spreadsheets/d/${file.id}/edit"))) }
                catch (_: Exception) { error = "未找到可打开表格的应用" }
            }) { Text("打开表格") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("完成") } })
}
