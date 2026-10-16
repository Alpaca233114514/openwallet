package io.openwallet.demo.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Patterns
import android.util.Log
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
    val reportFailure: (String, GoogleAuthFeedback) -> Unit = { stage, feedback ->
        busy = false
        error = feedback.message
        // Do not log exception messages, returned intents, tokens, or account details.
        Log.w("OpenWalletAuth", "sheets stage=$stage ${feedback.diagnostic}")
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
                        synchronized(SheetsSync.lock) {
                            val gateway = GoogleSheetsGateway(context, settings.installKey)
                            gateway.verifyAccount(token, requestedEmail)
                            settings.connect(requestedEmail)
                            SheetsScheduler.restore(context)
                            SheetsScheduler.changed(context)
                        }
                    }
                    error = null
                } catch (e: ExportFailure) {
                    error = e.message
                    Log.w("OpenWalletAuth", "sheets stage=verify retryable=${e.retryable} needsAuthorization=${e.needsAuthorization}")
                }
                catch (e: Exception) { reportFailure("verify", GoogleAuthErrors.unexpected("验证 Google 账户失败", e)) }
                finally { busy = false }
            }
        }
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        Log.i("OpenWalletAuth", "sheets stage=activity_result resultCode=${result.resultCode} hasData=${result.data != null}")
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
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Google Sheets 自动导出") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = email, onValueChange = { email = it; error = null }, label = { Text("目标 Google 邮箱") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("sheets-email"))
            Text("已入账流水将自动更新到此账户的专用表格。", style = MaterialTheme.typography.bodyMedium)
            if (target.email.isNotBlank()) Text("当前账户：${target.email}")
            Button(enabled = !busy && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(), modifier = Modifier.fillMaxWidth(), onClick = {
                requestedEmail = email.trim().lowercase(java.util.Locale.ROOT)
                busy = true; error = null
                val serviceStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                if (serviceStatus != ConnectionResult.SUCCESS) {
                    reportFailure("services", GoogleAuthErrors.services(serviceStatus))
                    return@Button
                }
                Log.i("OpenWalletAuth", "sheets stage=request")
                client.authorize(SheetsAuthorization.request(requestedEmail)).addOnSuccessListener { result ->
                    Log.i("OpenWalletAuth", "sheets stage=request_result hasResolution=${result.hasResolution()}")
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sheets-error")) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("完成") } })
}
