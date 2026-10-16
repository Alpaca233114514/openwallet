package io.openwallet.demo.export

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** Access under DriveSync.lock. Account destinations and retry state survive process restarts. */
class DriveSettings(val prefs: SharedPreferences) : DriveExportSettings {
    constructor(context: Context) : this(context.getSharedPreferences("drive_export", Context.MODE_PRIVATE))
    override fun target() = SheetsTarget(prefs.getString("email", "").orEmpty(), prefs.getBoolean("enabled", false), prefs.getBoolean("needs_auth", false))
    private fun key(name: String) = "$name:${target().email}"
    val status: String get() = prefs.getString("status", "尚未连接").orEmpty()
    val lastSuccess: Long get() = prefs.getLong(key("last_success"), 0)
    val installKey: String get() = prefs.getString("install_key", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("install_key", it).commit())
    }
    override fun folderId(): String? = prefs.getString(key("folder"), null)
    override fun saveFolderId(id: String) { check(prefs.edit().putString(key("folder"), id).commit()) }
    override fun fileId(day: LocalDate): String? = prefs.getString(key("file:$day"), null)
    override fun saveFileId(day: LocalDate, id: String) { check(prefs.edit().putString(key("file:$day"), id).commit()) }
    override fun queue(): DriveQueue {
        val data = prefs.getString(key("queue"), null) ?: return DriveQueue(LocalDate.now())
        val json = JSONObject(data)
        val jobs = json.getJSONObject("pending")
        return DriveQueue(LocalDate.parse(json.getString("through")), jobs.keys().asSequence().associate { date ->
            val attempt = jobs.getJSONObject(date)
            LocalDate.parse(date) to DriveAttempt(attempt.getInt("failures"), attempt.getLong("nextAt"))
        })
    }
    override fun saveQueue(queue: DriveQueue) {
        val jobs = JSONObject()
        queue.pending.forEach { (day, attempt) -> jobs.put(day.toString(), JSONObject().put("failures", attempt.failures).put("nextAt", attempt.nextAt)) }
        check(prefs.edit().putString(key("queue"), JSONObject().put("through", queue.through.toString()).put("pending", jobs).toString()).commit())
    }
    fun connect(email: String) {
        check(prefs.edit().putString("email", email).putBoolean("enabled", true).putBoolean("needs_auth", false)
            .putString("status", "已连接，等待创建文件夹并导出").commit())
        saveQueue(queue().prepare(LocalDate.now()).retryNow())
    }
    fun retryNow() {
        saveQueue(queue().prepare(LocalDate.now()).retryNow())
        check(prefs.edit().putString("status", "已安排上传，联网后执行").commit())
    }
    fun pause() { check(prefs.edit().putBoolean("enabled", false).putString("status", "已暂停云盘导出").commit()) }
    override fun success(day: LocalDate, count: Int) {
        val pending = queue().pending
        val failed = pending.count { it.value.nextAt == Long.MAX_VALUE }
        val remaining = when {
            failed > 0 -> "；另有 $failed 天需立即重试"
            pending.isNotEmpty() -> "；另有 ${pending.size} 天待补传"
            else -> ""
        }
        check(prefs.edit().putLong(key("last_success"), System.currentTimeMillis()).putBoolean("needs_auth", false)
            .putString("status", "已上传 $day · $count 条流水$remaining").commit())
    }
    override fun failure(message: String, needsAuthorization: Boolean) {
        check(prefs.edit().putString("status", message).putBoolean("needs_auth", needsAuthorization).commit())
    }
}
