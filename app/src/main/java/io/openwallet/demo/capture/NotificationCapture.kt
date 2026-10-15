package io.openwallet.demo.capture

import android.content.SharedPreferences
import io.openwallet.demo.data.WalletStore

enum class CaptureResult { AUTO_POSTED, PENDING_REVIEW, DUPLICATE }

/** The listener and instrumentation tests use the same settings → parser → atomic ledger path. */
class NotificationCapture(private val store: WalletStore, private val prefs: SharedPreferences) {
    fun receive(packageName: String, title: String, body: String, time: Long, key: String): CaptureResult? {
        if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean(packageName, false)) return null
        val accountId = prefs.getLong("account:$packageName", prefs.getLong("account", -1))
        if (store.snapshot().accounts.none { it.id == accountId }) {
            prefs.edit().putString("last_result", "请为通知来源选择有效账户").apply()
            return null
        }
        val parsed = NotificationParser.parse(packageName, body, accountId, time, key, title) ?: return null
        val result = store.recordNotification(parsed.entry, autoPost = prefs.getBoolean("auto_post", true) && parsed.canAutoPost)
        val label = when (result) {
            CaptureResult.AUTO_POSTED -> "已自动记账"
            CaptureResult.PENDING_REVIEW -> "已放入待确认"
            CaptureResult.DUPLICATE -> "重复通知已跳过"
        }
        prefs.edit().putString("last_result", "$label · ${NotificationParser.packages[packageName]}").apply()
        return result
    }
}
