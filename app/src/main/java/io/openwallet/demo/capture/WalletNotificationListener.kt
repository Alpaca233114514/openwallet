package io.openwallet.demo.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.openwallet.demo.data.WalletStore
import java.util.concurrent.Executors

class WalletNotificationListener : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onListenerConnected() { super.onListenerConnected(); NotificationPoster.get(this).schedule() }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NotificationParser.packages) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean(sbn.packageName, false)) return
        worker.execute {
            runCatching {
                NotificationCapture(WalletStore.get(this), prefs).receive(sbn)
                NotificationPoster.get(this).schedule()
            }.onFailure {
                prefs.edit().putString("last_result", "通知记账失败，请检查账户或重复记录").apply()
            }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
