package io.openwallet.demo.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.*
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.domain.WalletState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ExcelSync { val lock = Any() }

object ExcelScheduler {
    private const val CHANGES = "excel-export-changes"
    private const val PERIODIC = "excel-export-periodic"
    fun restore(context: Context) {
        if (!ExcelSettings(context).enabled) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExcelWorker>(15, TimeUnit.MINUTES).build())
    }
    fun changed(context: Context) {
        if (!ExcelSettings(context).enabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGES, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ExcelWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun pause(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHANGES)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class ExcelExport(private val context: Context, private val settings: ExcelSettings) {
    /** Called under ExcelSync.lock, including directory switches and pause. */
    fun run(snapshot: () -> WalletState): ExportResult {
        if (!settings.enabled || settings.tree.isBlank()) return ExportResult.STOPPED
        // Background exports need a durable grant. A picker can leave temporary access alive
        // after the persistent permission is released, which must not keep automation enabled.
        if (context.contentResolver.persistedUriPermissions.none {
                it.uri.toString() == settings.tree && it.isReadPermission && it.isWritePermission
            }) {
            settings.failure("文件夹访问权限已失效，请重新选择文件夹", stop = true)
            return ExportResult.STOPPED
        }
        var cache: File? = null
        return try {
            val workbook = File.createTempFile("excel-export-", ".xlsx", context.cacheDir).also { cache = it }
            // Finish the workbook locally before touching the user's exported copy.
            val count = ExcelWorkbook.write(snapshot(), workbook.outputStream())
            val resolver = context.contentResolver
            val uri = settings.file.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: kotlin.run {
                val tree = Uri.parse(settings.tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                // CREATE_DOCUMENT picks a distinct name if an unrelated file already exists.
                val created = DocumentsContract.createDocument(resolver, parent, ExcelWorkbook.MIME, ExcelWorkbook.FILE_NAME)
                    ?: throw IOException("create failed")
                // Persist the URI before optional metadata lookup so a retry reuses this file.
                settings.saveFile(created.toString(), ExcelWorkbook.FILE_NAME)
                val name = resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (!name.isNullOrBlank()) settings.saveFile(created.toString(), name)
                created
            }
            val output = resolver.openOutputStream(uri, "rwt") ?: throw IOException("open failed")
            output.use { out -> workbook.inputStream().use { it.copyTo(out) } }
            settings.success(count)
            ExportResult.DONE
        } catch (_: SecurityException) {
            settings.failure("文件夹访问权限已失效，请重新选择文件夹", stop = true)
            ExportResult.STOPPED
        } catch (_: java.io.FileNotFoundException) {
            settings.missingFile()
            settings.failure("导出文件或文件夹不可用，请重新选择文件夹", stop = true)
            ExportResult.STOPPED
        } catch (e: IllegalArgumentException) {
            settings.failure(e.message?.takeIf { it.startsWith("流水") } ?: "文件夹不可用，请重新选择文件夹", stop = true)
            ExportResult.STOPPED
        } catch (_: IOException) {
            settings.failure("文件写入失败，请检查存储空间，将自动重试")
            ExportResult.RETRY
        } finally { cache?.delete() }
    }
}

class ExcelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        synchronized(ExcelSync.lock) {
            if (isStopped) return@synchronized Result.success()
            val settings = ExcelSettings(applicationContext)
            try {
                when (ExcelExport(applicationContext, settings).run { WalletStore.get(applicationContext).snapshot() }) {
                    ExportResult.RETRY -> if (runAttemptCount < 5) Result.retry() else {
                        settings.failure("多次写入失败，等待下次后台检查或手动重试")
                        Result.failure()
                    }
                    ExportResult.DONE, ExportResult.STOPPED -> Result.success()
                }
            } catch (_: Exception) {
                settings.failure("导出未完成，请检查文件夹后重试")
                Result.failure()
            }
        }
    }
}
