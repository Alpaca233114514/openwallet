package io.openwallet.demo.export

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SheetsSync {
    // Serializes worker snapshots, account changes and pause; an in-flight request finishes before switching.
    val lock = Any()
}

object SheetsScheduler {
    private const val CHANGES = "sheets-export-changes"
    private const val PERIODIC = "sheets-export-periodic"
    fun restore(context: Context) {
        // Retire persisted cloud work on upgrade; keep the account and remote file metadata.
        synchronized(SheetsSync.lock) {
            if (SheetsSettings(context).target().enabled) SheetsSettings(context).pause()
            pause(context)
        }
    }
    fun changed(context: Context) {
        // Legacy callers cannot schedule another Google Sheets upload.
        restore(context)
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
            // An old WorkManager request can survive an APK upgrade. Never contact Google here.
            SheetsScheduler.restore(applicationContext)
            Result.success()
        }
    }
}
