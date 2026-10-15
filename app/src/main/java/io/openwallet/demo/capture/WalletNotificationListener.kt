package io.openwallet.demo.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.openwallet.demo.data.WalletStore
import java.util.concurrent.Executors

class WalletNotificationListener : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NotificationParser.packages) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean(sbn.packageName, false)) return
        val extras = sbn.notification.extras
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        // 'when' is stable across updates for many apps; a new timestamp is still screened for duplicates below.
        val eventTime = sbn.notification.`when`.takeIf { it > 0 && it in (sbn.postTime - 86_400_000)..(sbn.postTime + 60_000) } ?: sbn.postTime
        worker.execute {
            runCatching {
                NotificationCapture(WalletStore.get(this), prefs).receive(sbn.packageName, title, body, eventTime, sbn.key + ":" + eventTime)
            }.onFailure {
                prefs.edit().putString("last_result", "通知记账失败，请检查账户或重复记录").apply()
            }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
