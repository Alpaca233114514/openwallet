package io.openwallet.demo.capture

import android.content.SharedPreferences
import android.app.Notification
import android.service.notification.StatusBarNotification
import io.openwallet.demo.data.WalletStore

enum class CaptureResult { AUTO_POSTED, BUFFERED, PENDING_REVIEW, DUPLICATE }

/** The listener and instrumentation tests use the same settings → parser → atomic ledger path. */
class NotificationCapture(private val store: WalletStore, private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis, private val bufferMillis: Long = 8_000) {
    fun receive(sbn: StatusBarNotification): CaptureResult? {
        if (sbn.packageName !in NotificationParser.packages || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = sbn.notification.extras
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val eventTime = sbn.notification.`when`.takeIf { it > 0 && it in (sbn.postTime - 86_400_000)..(sbn.postTime + 60_000) } ?: sbn.postTime
        return receive(sbn.packageName, title, body, eventTime, sbn.key + ":" + eventTime)
    }

    fun receive(packageName: String, title: String, body: String, time: Long, key: String): CaptureResult? {
        if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean(packageName, false)) return null
        val accountId = prefs.getLong("account:$packageName", prefs.getLong("account", -1))
        if (store.snapshot().accounts.none { it.id == accountId }) {
            prefs.edit().putString("last_result", "请为通知来源选择有效账户").apply()
            return null
        }
        val parsed = NotificationParser.parse(packageName, body, accountId, time, key, title) ?: return null
        val result = store.recordNotification(parsed.entry, autoPost = prefs.getBoolean("auto_post", true) && parsed.canAutoPost,
            postAt = if (bufferMillis > 0) now() + bufferMillis else 0)
        val label = when (result) {
            CaptureResult.AUTO_POSTED -> "已自动记账"
            CaptureResult.BUFFERED -> "等待通知合并"
            CaptureResult.PENDING_REVIEW -> "已放入待确认"
            CaptureResult.DUPLICATE -> "重复通知已跳过"
        }
        prefs.edit().putString("last_result", "$label · ${NotificationParser.packages[packageName]}").apply()
        return result
    }
}
