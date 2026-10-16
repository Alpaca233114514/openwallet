package io.openwallet.demo.capture

import android.content.Context
import io.openwallet.demo.data.WalletStore
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Deadlines live in SQLite; a listener reconnect or app restart resumes outstanding work. */
class NotificationPoster internal constructor(private val store: WalletStore) : Closeable {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var scheduled: ScheduledFuture<*>? = null

    @Synchronized fun schedule() {
        if (worker.isShutdown) return
        scheduled?.cancel(false)
        val due = store.nextNotificationPostAt() ?: return
        scheduled = worker.schedule({
            val success = runCatching { store.flushBufferedNotifications() }.isSuccess
            if (success) schedule() else synchronized(this) {
                if (!worker.isShutdown) scheduled = worker.schedule({ schedule() }, 8, TimeUnit.SECONDS)
            }
        }, (due - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        synchronized(this) { scheduled?.cancel(false); worker.shutdownNow() }
        worker.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        @Volatile private var instance: NotificationPoster? = null
        fun get(context: Context): NotificationPoster = instance ?: synchronized(this) {
            instance ?: NotificationPoster(WalletStore.get(context.applicationContext)).also { instance = it }
        }
    }
}
