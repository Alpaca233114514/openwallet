package io.openwallet.demo.export

import io.openwallet.demo.domain.*

data class SheetsTarget(val email: String = "", val enabled: Boolean = false, val needsAuthorization: Boolean = false)
data class SheetFile(val id: String, val sheetId: Int)

interface ExportSettings {
    fun target(): SheetsTarget
    fun file(): SheetFile?
    fun saveFile(file: SheetFile)
    fun success(count: Int)
    fun failure(message: String, needsAuthorization: Boolean = false)
}

interface SheetsGateway {
    fun authorize(email: String): String
    fun verifyAccount(token: String, email: String)
    fun findOrCreate(token: String): SheetFile
    fun replace(token: String, file: SheetFile, rows: List<List<String>>)
    fun invalidate(token: String)
}

class ExportFailure(val retryable: Boolean, val needsAuthorization: Boolean, message: String) : Exception(message)
enum class ExportResult { DONE, RETRY, STOPPED }

/** Replaces the dedicated table with a snapshot, so retries never append duplicate transactions. */
class SheetsExport(private val settings: ExportSettings, private val gateway: SheetsGateway, private val snapshot: () -> WalletState) {
    fun run(): ExportResult {
        val target = settings.target()
        if (!target.enabled || target.email.isBlank() || target.needsAuthorization) return ExportResult.STOPPED
        var token: String? = null
        return try {
            token = gateway.authorize(target.email)
            gateway.verifyAccount(token, target.email)
            val rows = SheetsRows.from(snapshot())
            val file = settings.file() ?: gateway.findOrCreate(token).also(settings::saveFile)
            gateway.replace(token, file, rows)
            settings.success(rows.size - 1)
            ExportResult.DONE
        } catch (e: ExportFailure) {
            if (e.needsAuthorization && token != null) runCatching { gateway.invalidate(token) }
            settings.failure(e.message ?: "导出失败", e.needsAuthorization)
            if (e.retryable) ExportResult.RETRY else ExportResult.STOPPED
        } catch (_: java.io.IOException) {
            settings.failure("网络连接失败，将自动重试")
            ExportResult.RETRY
        }
    }
}

object SheetsRows {
    val header = listOf("时间", "账户", "类型", "金额（元）", "交易方", "分类", "流水号", "来源", "本地记录 ID")
    fun from(state: WalletState): List<List<String>> {
        val accounts = state.accounts.associateBy { it.id }
        return listOf(header) + state.entries.filterNot { it.pending }.sortedWith(compareBy<Entry> { it.time }.thenBy { it.id }).map { e ->
            listOf(Dates.text(e.time), accounts[e.accountId]?.name ?: error("流水账户不存在"),
                if (e.direction == Direction.INCOME) "收入" else "支出", Money.decimal(e.amount),
                e.merchant, e.category, e.externalId, e.source, e.id.toString())
        }
    }
}
