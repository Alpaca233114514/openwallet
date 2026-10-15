package io.openwallet.demo.export

import android.content.Context
import androidx.work.*
import io.openwallet.demo.data.WalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SheetsSync {
    // Serializes worker snapshots, account changes and pause; an in-flight request finishes before switching.
    val lock = Any()
}

object SheetsScheduler {
    private const val CHANGES = "sheets-export-changes"
    private const val PERIODIC = "sheets-export-periodic"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun restore(context: Context) {
        if (!SheetsSettings(context).target().enabled) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SheetsWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
    }
    fun changed(context: Context) {
        val target = SheetsSettings(context).target()
        if (!target.enabled || target.needsAuthorization) return
        // Chaining (rather than KEEP) preserves changes arriving after the active worker took its snapshot.
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGES, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SheetsWorker>().setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun pause(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHANGES)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class SheetsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        synchronized(SheetsSync.lock) {
            if (isStopped) return@synchronized Result.success()
            val settings = SheetsSettings(applicationContext)
            try {
                when (SheetsExport(settings, GoogleSheetsGateway(applicationContext, settings.installKey), { WalletStore.get(applicationContext).snapshot() }).run()) {
                    ExportResult.RETRY -> if (runAttemptCount < 5) Result.retry() else {
                        settings.failure("多次导出失败，等待下一次后台检查或手动重试")
                        Result.failure()
                    }
                    ExportResult.DONE, ExportResult.STOPPED -> Result.success()
                }
            } catch (_: Exception) {
                settings.failure("导出未完成，请重试或检查应用配置")
                Result.failure()
            }
        }
    }
}
