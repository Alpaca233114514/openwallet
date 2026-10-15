package io.openwallet.demo.export

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Access changes and exports under SheetsSync.lock; no tokens are persisted here. */
class SheetsSettings(val prefs: SharedPreferences) : ExportSettings {
    constructor(context: Context) : this(context.getSharedPreferences("sheets_export", Context.MODE_PRIVATE))
    override fun target() = SheetsTarget(prefs.getString("email", "").orEmpty(), prefs.getBoolean("enabled", false), prefs.getBoolean("needs_auth", false))
    val status: String get() = prefs.getString("status", "尚未连接").orEmpty()
    val lastSuccess: Long get() = prefs.getLong("last_success:${target().email}", 0)
    val installKey: String get() = prefs.getString("install_key", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("install_key", it).commit())
    }
    override fun file(): SheetFile? {
        val email = target().email
        val id = prefs.getString("file:$email", null) ?: return null
        return SheetFile(id, prefs.getInt("sheet:$email", 0))
    }
    fun connect(email: String) {
        check(prefs.edit().putString("email", email).putBoolean("enabled", true)
            .putBoolean("needs_auth", false).putString("status", "已连接，等待导出").commit())
    }
    fun pause() {
        check(prefs.edit().putBoolean("enabled", false).putString("status", "已暂停自动导出").commit())
    }
    override fun saveFile(file: SheetFile) {
        val email = target().email
        check(prefs.edit().putString("file:$email", file.id).putInt("sheet:$email", file.sheetId).commit())
    }
    override fun success(count: Int) {
        check(prefs.edit().putLong("last_success:${target().email}", System.currentTimeMillis())
            .putString("status", "已导出 $count 条流水").putBoolean("needs_auth", false).commit())
    }
    override fun failure(message: String, needsAuthorization: Boolean) {
        check(prefs.edit().putString("status", message).putBoolean("needs_auth", needsAuthorization).commit())
    }
}
