package io.openwallet.demo.export

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class GoogleSheetsGateway(context: Context, private val installKey: String) : SheetsGateway {
    private val authorization = GoogleAccountAuthorization(context)
    override fun authorize(email: String) = authorization.authorize(email)
    override fun verifyAccount(token: String, email: String) = authorization.verifyAccount(token, email)
    override fun invalidate(token: String) = authorization.invalidate(token)
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

    private fun request(method: String, url: String, token: String, body: JSONObject? = null) = GoogleHttp.request(method, url, token, body)

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
