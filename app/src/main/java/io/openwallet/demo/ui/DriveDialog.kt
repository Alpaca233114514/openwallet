package io.openwallet.demo.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import io.openwallet.demo.domain.Dates
import io.openwallet.demo.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun DriveDialog(settingsOverride: DriveSettings? = null, dismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { settingsOverride ?: DriveSettings(context) }
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
    val reportFailure: (String, GoogleAuthFeedback) -> Unit = { stage, feedback ->
        busy = false
        error = feedback.message
        // Do not log exception messages, returned intents, tokens, or account details.
        Log.w("OpenWalletAuth", "drive stage=$stage ${feedback.diagnostic}")
    }
    val finishAuthorization: (AuthorizationResult) -> Unit = { result ->
        val token = result.accessToken
        if (token.isNullOrBlank() || result.hasResolution() || requestedEmail.isBlank()) {
            reportFailure("token", GoogleAuthFeedback("Google 未完成授权，未取得访问权限，请重新连接", "incompleteResult=true"))
        } else {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        synchronized(DriveSync.lock) {
                            val gateway = GoogleAccountAuthorization(context)
                            gateway.verifyAccount(token, requestedEmail)
                            settings.connect(requestedEmail)
                            if (settingsOverride == null) {
                                DriveScheduler.restore(context)
                                DriveScheduler.changed(context)
                            }
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
        Log.i("OpenWalletAuth", "drive stage=activity_result resultCode=${result.resultCode} hasData=${result.data != null}")
        if (result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true) {
            reportFailure("launch", GoogleAuthFeedback("系统未能打开 Google 授权页面，请重试", "intentSenderFailed=true"))
            return@rememberLauncherForActivityResult
        }
        when (val outcome = readGoogleAuthResult(result.resultCode, result.data != null) {
            client.getAuthorizationResultFromIntent(result.data)
        }) {
            is GoogleAuthOutcome.Success -> finishAuthorization(outcome.result)
            is GoogleAuthOutcome.Failure -> reportFailure("activity_result", outcome.feedback)
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Google 云盘每日 CSV") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = email, onValueChange = { email = it; error = null }, label = { Text("目标 Google 邮箱") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("drive-email"))
            Text("授权后创建「OpenWallet 每日 CSV」文件夹，每天保存当天已入账流水。", style = MaterialTheme.typography.bodyMedium)
            Text("失败每隔至少 15 分钟重试，最多 5 次；系统省电可能延后。", style = MaterialTheme.typography.bodyMedium)
            if (target.email.isNotBlank()) Text("当前账户：${target.email}")
            Button(enabled = !busy && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(), modifier = Modifier.fillMaxWidth(), onClick = {
                requestedEmail = email.trim().lowercase(java.util.Locale.ROOT)
                busy = true; error = null
                val serviceStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                if (serviceStatus != ConnectionResult.SUCCESS) {
                    reportFailure("services", GoogleAuthErrors.services(serviceStatus))
                    return@Button
                }
                Log.i("OpenWalletAuth", "drive stage=request")
                client.authorize(SheetsAuthorization.request(requestedEmail)).addOnSuccessListener { result ->
                    Log.i("OpenWalletAuth", "drive stage=request_result hasResolution=${result.hasResolution()}")
                    if (result.hasResolution()) {
                        val intent = result.pendingIntent
                        if (intent == null) { reportFailure("launch", GoogleAuthFeedback("Google 未提供授权页面，请重试", "missingPendingIntent=true")) }
                        else try { authorization.launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
                        catch (e: Exception) { reportFailure("launch", GoogleAuthErrors.unexpected("无法打开 Google 授权页面", e)) }
                    } else finishAuthorization(result)
                }.addOnFailureListener { e ->
                    reportFailure("request", if (e is ApiException) GoogleAuthErrors.api(e.statusCode)
                        else GoogleAuthErrors.unexpected("请求 Google 授权失败", e))
                }
            }) { Text(if (target.email.isBlank()) "连接并开启" else "授权并启用") }
            Text(remember(revision) { settings.status }, modifier = Modifier.testTag("drive-status"))
            val lastSuccess = remember(revision) { settings.lastSuccess }
            if (lastSuccess > 0) Text("上次成功：${Dates.text(lastSuccess)}", style = MaterialTheme.typography.bodyMedium)
            if (target.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && !target.needsAuthorization, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { synchronized(DriveSync.lock) {
                                    settings.retryNow()
                                    if (settingsOverride == null) DriveScheduler.changed(context)
                                } }
                                error = null
                            } catch (_: Exception) { error = "无法安排上传，请检查设备存储后重试" }
                            finally { busy = false }
                        }
                    }) { Text("立即重试") }
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try { withContext(Dispatchers.IO) { synchronized(DriveSync.lock) {
                                settings.pause()
                                if (settingsOverride == null) DriveScheduler.pause(context)
                            } }; error = null }
                            catch (_: Exception) { error = "暂停失败，请重试" }
                            finally { busy = false }
                        }
                    }) { Text("暂停云盘导出") }
                }
            }
            val folder = remember(revision) { settings.folderId() }
            if (folder != null && lastSuccess > 0) TextButton(enabled = !busy, onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/folders/$folder"))) }
                catch (_: Exception) { error = "未找到可打开云盘的应用" }
            }) { Text("打开云盘文件夹") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("drive-error")) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("完成") } })
}
