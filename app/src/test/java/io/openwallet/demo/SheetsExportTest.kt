package io.openwallet.demo

import io.openwallet.demo.domain.*
import io.openwallet.demo.export.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SheetsExportTest {
    private val account = Account(1, "银行卡", 0, 0)
    private val entry = Entry(12, 1, 1000, 2850, Direction.EXPENSE, "=IMPORTXML(\"test\")", "餐饮", externalId = "000012")
    private class Settings : ExportSettings {
        var current = SheetsTarget("one@example.com", true)
        val files = mutableMapOf<String, SheetFile>()
        var count = -1
        var error = ""
        override fun target() = current
        override fun file() = files[current.email]
        override fun saveFile(file: SheetFile) { files[current.email] = file }
        override fun success(count: Int) { this.count = count }
        override fun failure(message: String, needsAuthorization: Boolean) {
            error = message; current = current.copy(needsAuthorization = needsAuthorization)
        }
    }
    private class Gateway : SheetsGateway {
        val emails = mutableListOf<String>()
        val writes = mutableListOf<Pair<SheetFile, List<List<String>>>>()
        var fail: Exception? = null
        var wrongAccount = false
        var creates = 0
        var invalidations = 0
        override fun authorize(email: String): String { emails.add(email); return "test-token" }
        override fun verifyAccount(token: String, email: String) {
            if (wrongAccount) throw ExportFailure(false, true, "wrong account")
        }
        override fun findOrCreate(token: String) = SheetFile("file-${++creates}", 0)
        override fun replace(token: String, file: SheetFile, rows: List<List<String>>) {
            writes.add(file to rows)
            fail?.let { throw it }
        }
        override fun invalidate(token: String) { invalidations++ }
    }
    @Test fun exportsOnlyPostedRowsWithExactCentsAndStableOrder() {
        val rows = SheetsRows.from(WalletState(listOf(account), listOf(entry.copy(id = 13, time = 2000), entry.copy(id = 99, pending = true), entry)))
        assertEquals(3, rows.size)
        assertEquals("12", rows[1][8]); assertEquals("28.50", rows[1][3]); assertEquals("000012", rows[1][6])
        assertEquals(entry.merchant, rows[1][4])
        assertEquals("13", rows[2][8])
    }
    @Test fun disabledOrAuthorizationRequiredNeverTouchesGoogle() {
        val settings = Settings(); val gateway = Gateway()
        val export = SheetsExport(settings, gateway) { error("must not read ledger") }
        settings.current = settings.current.copy(enabled = false)
        assertEquals(ExportResult.STOPPED, export.run())
        settings.current = settings.current.copy(enabled = true, needsAuthorization = true)
        assertEquals(ExportResult.STOPPED, export.run())
        assertTrue(gateway.emails.isEmpty())
    }
    @Test fun mismatchedAccountCannotCreateOrWrite() {
        val settings = Settings(); val gateway = Gateway().apply { wrongAccount = true }
        assertEquals(ExportResult.STOPPED, SheetsExport(settings, gateway) { error("no ledger upload") }.run())
        assertTrue(settings.current.needsAuthorization)
        assertEquals(0, gateway.creates); assertTrue(gateway.writes.isEmpty()); assertEquals(1, gateway.invalidations)
    }
    @Test fun lostWriteResponseRetriesSameFileAndSnapshotWithoutAppend() {
        val settings = Settings(); val gateway = Gateway().apply { fail = IOException("response lost") }
        val export = SheetsExport(settings, gateway) { WalletState(listOf(account), listOf(entry)) }
        assertEquals(ExportResult.RETRY, export.run()); assertEquals(-1, settings.count)
        gateway.fail = null
        assertEquals(ExportResult.DONE, export.run())
        assertEquals(1, gateway.creates); assertEquals(gateway.writes[0], gateway.writes[1]); assertEquals(1, settings.count)
    }
    @Test fun removalFromPostedLedgerReplacesWithHeaderOnly() {
        val settings = Settings(); val gateway = Gateway()
        var state = WalletState(listOf(account), listOf(entry))
        val export = SheetsExport(settings, gateway) { state }
        export.run(); state = state.copy(entries = listOf(entry.copy(pending = true))); export.run()
        assertEquals(listOf(SheetsRows.header), gateway.writes.last().second)
        assertEquals(0, settings.count)
    }
    @Test fun switchingAccountsNeverReusesOtherAccountsFile() {
        val settings = Settings(); val gateway = Gateway()
        val export = SheetsExport(settings, gateway) { WalletState() }
        export.run(); settings.current = SheetsTarget("two@example.com", true); export.run()
        settings.current = SheetsTarget("one@example.com", true); export.run()
        assertEquals(listOf("one@example.com", "two@example.com", "one@example.com"), gateway.emails)
        assertEquals(listOf("file-1", "file-2", "file-1"), gateway.writes.map { it.first.id })
    }
    @Test fun transientFailuresRetryButRevokedAuthorizationStops() {
        val settings = Settings(); val gateway = Gateway()
        val export = SheetsExport(settings, gateway) { WalletState() }
        gateway.fail = ExportFailure(true, false, "rate limited")
        assertEquals(ExportResult.RETRY, export.run()); assertFalse(settings.current.needsAuthorization)
        gateway.fail = ExportFailure(false, true, "revoked")
        assertEquals(ExportResult.STOPPED, export.run()); assertTrue(settings.current.needsAuthorization)
        assertEquals(1, gateway.invalidations)
        val calls = gateway.emails.size
        assertEquals(ExportResult.STOPPED, export.run()); assertEquals(calls, gateway.emails.size)
    }
}
