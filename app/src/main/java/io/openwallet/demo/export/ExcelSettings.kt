package io.openwallet.demo.export

import android.content.Context
import android.content.SharedPreferences

class ExcelSettings(val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("excel_export", Context.MODE_PRIVATE))
    val enabled get() = prefs.getBoolean("enabled", false)
    val tree get() = prefs.getString("tree", "").orEmpty()
    val folderName get() = prefs.getString("folder_name", "").orEmpty()
    val file get() = prefs.getString("file:$tree", "").orEmpty()
    val fileName get() = prefs.getString("file_name:$tree", ExcelWorkbook.FILE_NAME).orEmpty()
    val status get() = prefs.getString("status", "尚未选择文件夹").orEmpty()
    val lastSuccess get() = prefs.getLong("last_success:$tree", 0)
    fun connect(uri: String, name: String) {
        check(prefs.edit().putString("tree", uri).putString("folder_name", name).putBoolean("enabled", true)
            .putString("status", "已开启，等待导出").commit())
    }
    fun pause() { check(prefs.edit().putBoolean("enabled", false).putString("status", "已暂停自动导出").commit()) }
    fun saveFile(uri: String, name: String) {
        check(prefs.edit().putString("file:$tree", uri).putString("file_name:$tree", name).commit())
    }
    fun missingFile() {
        check(prefs.edit().putString("missing_file:$tree", file).remove("file:$tree").remove("file_name:$tree").commit())
    }
    fun success(count: Int) {
        check(prefs.edit().putLong("last_success:$tree", System.currentTimeMillis())
            .putString("status", "已导出 $count 条流水").commit())
    }
    fun failure(message: String, stop: Boolean = false) {
        val edit = prefs.edit().putString("status", message)
        if (stop) edit.putBoolean("enabled", false)
        check(edit.commit())
    }
}
