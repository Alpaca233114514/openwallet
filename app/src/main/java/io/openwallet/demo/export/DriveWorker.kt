package io.openwallet.demo.export

import android.content.Context
import androidx.work.*
import io.openwallet.demo.data.WalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object DriveSync { val lock = Any() }

object DriveScheduler {
    private const val CHANGES = "drive-csv-changes"
    private const val PERIODIC = "drive-csv-periodic"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun restore(context: Context) {
        if (!DriveSettings(context).target().enabled) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
    }
    fun changed(context: Context) {
        val target = DriveSettings(context).target()
        if (!target.enabled || target.needsAuthorization) return
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGES, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DriveWorker>().setConstraints(network).build())
    }
    fun pause(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHANGES)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class DriveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        synchronized(DriveSync.lock) {
            if (isStopped) return@synchronized Result.success()
            val settings = DriveSettings(applicationContext)
            try {
                DriveExport(settings, GoogleDriveGateway(applicationContext, settings.installKey),
                    { WalletStore.get(applicationContext).snapshot() }).run()
                // Retry deadlines/counts are persisted by DriveExport, independent of WorkManager attempts.
                Result.success()
            } catch (_: Exception) {
                settings.failure("导出状态无法保存，请检查设备存储后重试")
                Result.failure()
            }
        }
    }
}
