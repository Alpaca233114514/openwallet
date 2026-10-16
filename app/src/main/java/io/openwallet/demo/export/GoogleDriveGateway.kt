package io.openwallet.demo.export

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID

class GoogleDriveGateway(context: Context, private val installKey: String, private val http: GoogleTransport = GoogleHttp) : DriveGateway {
    private val authorization = GoogleAccountAuthorization(context)
    override fun authorize(email: String) = authorization.authorize(email)
    override fun verifyAccount(token: String, email: String) = authorization.verifyAccount(token, email)
    override fun invalidate(token: String) = authorization.invalidate(token)
    override fun reserveId(token: String): String = request("GET", "$API/generateIds?count=1&space=drive&type=files", token)
        .getJSONArray("ids").getString(0)

    override fun ensureFolder(token: String, id: String) {
        if (metadata(token, id) == null) {
            try {
                request("POST", "$API?fields=id", token, JSONObject().put("id", id).put("name", "OpenWallet 每日 CSV")
                    .put("mimeType", FOLDER).put("appProperties", properties("folder")))
            } catch (e: ExportFailure) { if (e.statusCode != 409) throw e }
        }
        verify(metadata(token, id), FOLDER, "folder")
    }

    override fun upload(token: String, folder: String, id: String, day: LocalDate, csv: String) {
        var file = metadata(token, id)
        if (file == null) {
            val metadata = JSONObject().put("id", id).put("name", "openwallet-$day.csv").put("mimeType", "text/csv")
                .put("parents", JSONArray().put(folder)).put("appProperties", properties(day.toString()))
            val boundary = "openwallet-${UUID.randomUUID()}"
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: text/csv; charset=UTF-8\r\n\r\n$csv\r\n--$boundary--\r\n"
            try {
                http.request("POST", "$UPLOAD?uploadType=multipart&fields=id", token, body.toByteArray(Charsets.UTF_8), "multipart/related; boundary=$boundary")
                return
            } catch (e: ExportFailure) { if (e.statusCode != 409) throw e }
            // A prior POST may have succeeded while its response was lost. Reuse its reserved ID.
            file = metadata(token, id)
        }
        verify(file, "text/csv", day.toString(), folder)
        http.request("PATCH", "$UPLOAD/${encode(id)}?uploadType=media&fields=id", token, csv.toByteArray(Charsets.UTF_8), "text/csv; charset=UTF-8")
    }

    private fun properties(kind: String) = JSONObject().put("openwalletCsv", installKey).put("day", kind)
    private fun metadata(token: String, id: String): JSONObject? = try {
        request("GET", "$API/${encode(id)}?fields=id,mimeType,ownedByMe,trashed,appProperties,parents", token)
    } catch (e: ExportFailure) { if (e.statusCode == 404) null else throw e }
    private fun verify(file: JSONObject?, mime: String, kind: String, parent: String? = null) {
        val props = file?.optJSONObject("appProperties")
        val parents = file?.optJSONArray("parents")
        if (file == null || !file.optBoolean("ownedByMe") || file.optBoolean("trashed") || file.optString("mimeType") != mime ||
            props?.optString("openwalletCsv") != installKey || props.optString("day") != kind ||
            (parent != null && (parents == null || (0 until parents.length()).none { parents.getString(it) == parent }))) {
            throw ExportFailure(false, false, "云盘导出目标已移除或被更改，请恢复原文件夹和 CSV")
        }
    }
    private fun request(method: String, url: String, token: String, body: JSONObject? = null) =
        http.request(method, url, token, body?.toString()?.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    companion object {
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER = "application/vnd.google-apps.folder"
    }
}
