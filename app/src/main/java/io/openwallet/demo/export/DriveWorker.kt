package io.openwallet.demo.export

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Cancels work persisted by versions that supported daily Drive CSV exports. */
object DriveScheduler {
    fun retire(context: Context) {
        val prefs = context.getSharedPreferences("drive_export", Context.MODE_PRIVATE)
        if (prefs.getBoolean("enabled", false)) {
            // Preserve account, remote file IDs and queue metadata for the user's records.
            prefs.edit().putBoolean("enabled", false).putString("status", "云盘每日 CSV 功能已移除").apply()
        }
        WorkManager.getInstance(context).cancelUniqueWork("drive-csv-changes")
        WorkManager.getInstance(context).cancelUniqueWork("drive-csv-periodic")
    }
}

/** Keep this class name so a surviving WorkManager request completes without uploading. */
class DriveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
