package io.openwallet.demo.export

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SheetsAuthorization {
    fun request(email: String): AuthorizationRequest = AuthorizationRequest.builder()
        .setAccount(Account(email, "com.google"))
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file"), Scope("https://www.googleapis.com/auth/userinfo.email")))
        .build()
}

class GoogleAccountAuthorization(context: Context) {
    private val client = Identity.getAuthorizationClient(context)
    fun authorize(email: String): String {
        val result = try {
            Tasks.await(client.authorize(SheetsAuthorization.request(email)), 45, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw ExportFailure(true, false, "Google 连接超时，将自动重试")
        } catch (e: ExecutionException) {
            val retry = (e.cause as? ApiException)?.statusCode in listOf(7, 8, 14, 15)
            throw ExportFailure(retry, !retry, if (retry) "Google 暂不可用，将自动重试" else "请重新授权 Google 账户；若持续失败，请检查 OAuth 配置")
        }
        if (result.hasResolution() || result.accessToken.isNullOrBlank()) {
            throw ExportFailure(false, true, "请重新授权 Google 账户")
        }
        return result.accessToken!!
    }
    fun verifyAccount(token: String, email: String) {
        val info = GoogleHttp.request("GET", "https://www.googleapis.com/oauth2/v3/userinfo", token)
        if (!info.optBoolean("email_verified") || !info.optString("email").equals(email.trim(), ignoreCase = true)) {
            throw ExportFailure(false, true, "授权账户与指定邮箱不一致，请重新连接")
        }
    }
    fun invalidate(token: String) {
        Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS)
    }
}

fun interface GoogleTransport {
    fun request(method: String, url: String, token: String, body: ByteArray?, contentType: String): JSONObject
}

object GoogleHttp : GoogleTransport {
    fun request(method: String, url: String, token: String, body: JSONObject? = null) =
        request(method, url, token, body?.toString()?.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8")
    override fun request(method: String, url: String, token: String, body: ByteArray?, contentType: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Never surface response bodies, tokens, or ledger contents in logs/UI.
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val rateLimited = status == 403 && listOf("rateLimitExceeded", "userRateLimitExceeded", "RESOURCE_EXHAUSTED").any { it in error }
                val retry = status == 408 || status == 429 || status >= 500 || rateLimited
                val message = when {
                    retry -> "Google 服务暂不可用（$status），将自动重试"
                    status == 401 -> "Google 授权已失效，请重新授权"
                    status == 403 -> "Google 拒绝访问，请检查 API 启用状态与账户授权"
                    status == 404 -> "导出文件不存在，请在 Google Drive 中恢复原文件"
                    else -> "导出失败（$status），请检查 Google 配置后重试"
                }
                throw ExportFailure(retry, status == 401 || (status == 403 && !rateLimited), message, status)
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { val text = it.readText(); if (text.isBlank()) JSONObject() else JSONObject(text) }
        } finally { connection.disconnect() }
    }
}
