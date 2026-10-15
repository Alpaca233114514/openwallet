package io.openwallet.demo.export

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SheetsAuthorization {
    fun request(email: String): AuthorizationRequest = AuthorizationRequest.builder()
        .setAccount(Account(email, "com.google"))
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file"), Scope("https://www.googleapis.com/auth/userinfo.email")))
        .build()
}

class GoogleSheetsGateway(context: Context, private val installKey: String) : SheetsGateway {
    private val client = Identity.getAuthorizationClient(context)
    override fun authorize(email: String): String {
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
    override fun verifyAccount(token: String, email: String) {
        val info = request("GET", "https://www.googleapis.com/oauth2/v3/userinfo", token)
        if (!info.optBoolean("email_verified") || !info.optString("email").equals(email.trim(), ignoreCase = true)) {
            throw ExportFailure(false, true, "授权账户与指定邮箱不一致，请重新连接")
        }
    }
    override fun invalidate(token: String) {
        Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS)
    }
    override fun findOrCreate(token: String): SheetFile {
        // Recover a file whose creation succeeded even if its response/local save was interrupted.
        val query = "trashed = false and 'me' in owners and mimeType = 'application/vnd.google-apps.spreadsheet' and appProperties has { key='openwallet' and value='$installKey' }"
        val files = request("GET", "https://www.googleapis.com/drive/v3/files?q=${encode(query)}&fields=files(id)&orderBy=createdTime&pageSize=1", token).getJSONArray("files")
        val id = if (files.length() > 0) files.getJSONObject(0).getString("id") else {
            request("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token, JSONObject()
                .put("name", "OpenWallet 自动导出")
                .put("mimeType", "application/vnd.google-apps.spreadsheet")
                .put("appProperties", JSONObject().put("openwallet", installKey))).getString("id")
        }
        val sheets = metadata(token, id).getJSONArray("sheets")
        if (sheets.length() == 0) throw ExportFailure(false, false, "导出表缺少工作表，请在 Google Sheets 中恢复")
        return SheetFile(id, sheets.getJSONObject(0).getJSONObject("properties").getInt("sheetId"))
    }
    override fun replace(token: String, file: SheetFile, rows: List<List<String>>) {
        val owner = request("GET", "https://www.googleapis.com/drive/v3/files/${encode(file.id)}?fields=ownedByMe,appProperties,trashed", token)
        if (!owner.optBoolean("ownedByMe") || owner.optBoolean("trashed") || owner.optJSONObject("appProperties")?.optString("openwallet") != installKey) {
            throw ExportFailure(false, false, "导出表已移除或不再属于此账户，请恢复原表")
        }
        val sheets = metadata(token, file.id).getJSONArray("sheets")
        val properties = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties") }
            .firstOrNull { it.getInt("sheetId") == file.sheetId }
            ?: throw ExportFailure(false, false, "导出工作表已删除，请在 Google Sheets 中恢复")
        val grid = properties.getJSONObject("gridProperties")
        val body = SheetsPayload.replace(file.sheetId, rows, grid.getInt("rowCount"), grid.getInt("columnCount"))
        request("POST", "https://sheets.googleapis.com/v4/spreadsheets/${encode(file.id)}:batchUpdate", token, body)
    }
    private fun metadata(token: String, id: String) = request("GET", "https://sheets.googleapis.com/v4/spreadsheets/${encode(id)}?fields=sheets(properties)", token)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun request(method: String, url: String, token: String, body: JSONObject? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
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
                    status == 404 -> "导出表不存在，请在 Google Drive 中恢复原表"
                    else -> "导出失败（$status），请检查 Google 配置后重试"
                }
                throw ExportFailure(retry, status == 401 || (status == 403 && !rateLimited), message)
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }
}

object SheetsPayload {
    fun replace(sheetId: Int, rows: List<List<String>>, existingRows: Int, existingColumns: Int): JSONObject {
        val requests = JSONArray()
        val rowCount = maxOf(existingRows, rows.size)
        val columns = SheetsRows.header.size
        if (rows.size > existingRows || columns > existingColumns) requests.put(JSONObject().put("updateSheetProperties", JSONObject()
            .put("properties", JSONObject().put("sheetId", sheetId).put("gridProperties", JSONObject().put("rowCount", rowCount).put("columnCount", maxOf(columns, existingColumns))))
            .put("fields", "gridProperties.rowCount,gridProperties.columnCount")))
        val data = JSONArray()
        rows.forEach { row ->
            val cells = JSONArray()
            // Explicit stringValue preserves cents/IDs and prevents merchant text from becoming a formula.
            row.forEach { cells.put(JSONObject().put("userEnteredValue", JSONObject().put("stringValue", it))) }
            data.put(JSONObject().put("values", cells))
        }
        // A bounded range clears stale trailing rows in the same atomic request, including an empty ledger.
        requests.put(JSONObject().put("updateCells", JSONObject()
            .put("range", JSONObject().put("sheetId", sheetId).put("startRowIndex", 0).put("endRowIndex", rowCount).put("startColumnIndex", 0).put("endColumnIndex", columns))
            .put("rows", data).put("fields", "userEnteredValue")))
        return JSONObject().put("requests", requests)
    }
}
