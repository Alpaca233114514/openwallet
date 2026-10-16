package io.openwallet.demo.export

import io.openwallet.demo.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DriveAttempt(val failures: Int = 0, val nextAt: Long = 0)
data class DriveQueue(val through: LocalDate, val pending: Map<LocalDate, DriveAttempt> = emptyMap()) {
    fun prepare(today: LocalDate): DriveQueue {
        val jobs = pending.toMutableMap()
        // Revisit the previous day once at rollover to include its final transactions.
        var day = minOf(through, today)
        while (day <= today) {
            jobs.putIfAbsent(day, DriveAttempt())
            day = day.plusDays(1)
        }
        return DriveQueue(maxOf(through, today), jobs)
    }
    fun retryNow() = copy(pending = pending.mapValues { DriveAttempt() })
}

object DriveRetry {
    const val INTERVAL_MS = 15 * 60 * 1000L
    const val MAX_FAILURES = 6 // Initial attempt plus five retries.
    fun failed(previous: DriveAttempt, now: Long, retryable: Boolean): DriveAttempt {
        val failures = previous.failures + 1
        return DriveAttempt(failures, if (retryable && failures < MAX_FAILURES) now + INTERVAL_MS else Long.MAX_VALUE)
    }
}

interface DriveExportSettings {
    fun target(): SheetsTarget
    fun queue(): DriveQueue
    fun saveQueue(queue: DriveQueue)
    fun folderId(): String?
    fun saveFolderId(id: String)
    fun fileId(day: LocalDate): String?
    fun saveFileId(day: LocalDate, id: String)
    fun success(day: LocalDate, count: Int)
    fun failure(message: String, needsAuthorization: Boolean = false)
}

interface DriveGateway {
    fun authorize(email: String): String
    fun verifyAccount(token: String, email: String)
    fun invalidate(token: String)
    fun reserveId(token: String): String
    fun ensureFolder(token: String, id: String)
    fun upload(token: String, folder: String, id: String, day: LocalDate, csv: String)
}

object DailyCsv {
    fun entries(state: WalletState, day: LocalDate, zone: ZoneId) = state.entries
        .filter { !it.pending && Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() == day }
        .sortedWith(compareBy<Entry> { it.time }.thenBy { it.id })
    fun export(state: WalletState, day: LocalDate, zone: ZoneId) = LedgerCsv.export(entries(state, day, zone), state.accounts)
}

/** Durable per-day retries; the scheduler's polling never resets or bypasses their deadlines. */
class DriveExport(
    private val settings: DriveExportSettings,
    private val gateway: DriveGateway,
    private val snapshot: () -> WalletState,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun run() {
        val target = settings.target()
        if (!target.enabled || target.email.isBlank() || target.needsAuthorization) return
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        var queue = settings.queue().prepare(today)
        settings.saveQueue(queue)
        val due = queue.pending.filter { it.value.nextAt <= now() }.keys.sorted().take(3)
        if (due.isEmpty()) return
        var token: String? = null
        for (day in due) {
            try {
                if (token == null) {
                    token = gateway.authorize(target.email)
                    gateway.verifyAccount(token, target.email)
                }
                val folder = settings.folderId() ?: gateway.reserveId(token).also(settings::saveFolderId)
                gateway.ensureFolder(token, folder)
                val id = settings.fileId(day) ?: gateway.reserveId(token).also { settings.saveFileId(day, it) }
                val state = snapshot()
                gateway.upload(token, folder, id, day, DailyCsv.export(state, day, zone))
                queue = queue.copy(pending = queue.pending - day)
                settings.saveQueue(queue)
                settings.success(day, DailyCsv.entries(state, day, zone).size)
            } catch (e: Exception) {
                val failure = when (e) {
                    is ExportFailure -> e
                    is java.io.IOException -> ExportFailure(true, false, "网络连接失败")
                    else -> ExportFailure(false, false, "导出未完成，请检查应用配置")
                }
                val attempt = DriveRetry.failed(queue.pending.getValue(day), now(), failure.retryable)
                queue = queue.copy(pending = queue.pending + (day to attempt))
                settings.saveQueue(queue)
                val suffix = if (attempt.nextAt == Long.MAX_VALUE) "；请点立即重试" else "；15 分钟后重试（${attempt.failures}/5）"
                settings.failure("$day：${failure.message}" + if (failure.needsAuthorization) "" else suffix, failure.needsAuthorization)
                if (failure.needsAuthorization && token != null) runCatching { gateway.invalidate(token) }
                // A service/account failure should not consume every queued day's attempts in one run.
                break
            }
        }
    }
}
