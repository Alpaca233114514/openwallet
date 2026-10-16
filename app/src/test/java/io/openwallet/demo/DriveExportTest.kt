package io.openwallet.demo

import io.openwallet.demo.domain.*
import io.openwallet.demo.export.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.*

class DriveExportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 15)
    private fun time(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun entry(id: Long, time: String, pending: Boolean = false) = Entry(id, 1, time(time), 1234, Direction.EXPENSE, "=咖啡,店\n分店", "餐饮", externalId = "0001", pending = pending)
    private class Settings(day: LocalDate) : DriveExportSettings {
        var selected = SheetsTarget("wallet@example.com", true)
        var jobs = DriveQueue(day)
        var folder: String? = null
        val ids = mutableMapOf<LocalDate, String>()
        var message = ""
        override fun target() = selected
        override fun queue() = jobs
        override fun saveQueue(queue: DriveQueue) { jobs = queue }
        override fun folderId() = folder
        override fun saveFolderId(id: String) { folder = id }
        override fun fileId(day: LocalDate) = ids[day]
        override fun saveFileId(day: LocalDate, id: String) { ids[day] = id }
        override fun success(day: LocalDate, count: Int) { message = "$day:$count" }
        override fun failure(message: String, needsAuthorization: Boolean) { this.message = message; selected = selected.copy(needsAuthorization = needsAuthorization) }
    }
    private class Gateway : DriveGateway {
        var calls = 0
        var reservations = 0
        var invalidated = false
        var failure: Exception? = null
        val uploads = mutableListOf<Triple<String, LocalDate, String>>()
        override fun authorize(email: String): String { calls++; return "memory-only" }
        override fun verifyAccount(token: String, email: String) {}
        override fun invalidate(token: String) { invalidated = true }
        override fun reserveId(token: String) = "id-${++reservations}"
        override fun ensureFolder(token: String, id: String) {}
        override fun upload(token: String, folder: String, id: String, day: LocalDate, csv: String) {
            uploads.add(Triple(id, day, csv)); failure?.let { throw it }
        }
    }
    @Test fun localDayBoundariesPendingAndCsvEscaping() {
        val state = WalletState(listOf(Account(1, "银行", 0, 0)), listOf(
            entry(1, "2026-10-14T23:59:59"), entry(3, "2026-10-15T23:59:59"),
            entry(2, "2026-10-15T00:00:00"), entry(4, "2026-10-16T00:00:00"), entry(5, "2026-10-15T12:00:00", true)))
        assertEquals(listOf(2L, 3L), DailyCsv.entries(state, day, zone).map { it.id })
        val csv = DailyCsv.export(state, day, zone)
        assertTrue(csv.startsWith("\uFEFF")); assertTrue(csv.contains("'=咖啡,店\n分店"))
        val rows = LedgerCsv.tokenize(csv.removePrefix("\uFEFF"))
        assertEquals(3, rows.size); assertEquals("12.34", rows[1][3]); assertEquals("0001", rows[1][6])
        assertEquals(1, LedgerCsv.tokenize(DailyCsv.export(WalletState(), day, zone).removePrefix("\uFEFF")).size)
    }
    @Test fun retryDeadlineSurvivesNewRunnerAndRepeatedChangeTriggers() {
        val settings = Settings(day); val gateway = Gateway().apply { failure = IOException() }
        var now = time("2026-10-15T12:00:00")
        fun run() = DriveExport(settings, gateway, { WalletState() }, { now }, zone).run()
        run(); val deadline = now + DriveRetry.INTERVAL_MS
        repeat(3) { now += 1000; run() }
        assertEquals(1, gateway.calls); assertEquals(DriveAttempt(1, deadline), settings.jobs.pending[day])
        now = deadline; gateway.failure = null; run()
        assertEquals(2, gateway.calls); assertFalse(settings.jobs.pending.containsKey(day))
        assertEquals(2, gateway.reservations); assertEquals(gateway.uploads[0].first, gateway.uploads[1].first)
    }
    @Test fun fiveRetriesThenStopUntilManualResetWhileNewDayStillRuns() {
        val settings = Settings(day); val gateway = Gateway().apply { failure = IOException() }
        var now = time("2026-10-15T12:00:00")
        fun run() = DriveExport(settings, gateway, { WalletState() }, { now }, zone).run()
        repeat(6) { run(); now += DriveRetry.INTERVAL_MS }
        assertEquals(6, gateway.calls); assertEquals(Long.MAX_VALUE, settings.jobs.pending.getValue(day).nextAt)
        repeat(3) { run() }; assertEquals(6, gateway.calls)
        now = time("2026-10-16T12:00:00"); gateway.failure = null; run()
        assertEquals(day.plusDays(1), gateway.uploads.last().second)
        assertTrue(settings.jobs.pending.containsKey(day))
        settings.jobs = settings.jobs.retryNow(); run()
        assertFalse(settings.jobs.pending.containsKey(day))
    }
    @Test fun midnightAndMissedDaysUseOriginalDatesAndFinalizePreviousDay() {
        val settings = Settings(day); val gateway = Gateway()
        var now = time("2026-10-15T12:00:00")
        var state = WalletState(listOf(Account(1, "银行", 0, 0)))
        fun run() = DriveExport(settings, gateway, { state }, { now }, zone).run()
        run()
        state = state.copy(entries = listOf(entry(1, "2026-10-15T23:59:59")))
        now = time("2026-10-17T01:00:00"); run()
        assertEquals(listOf(day, day, day.plusDays(1), day.plusDays(2)), gateway.uploads.map { it.second })
        assertTrue(gateway.uploads[1].third.contains("12.34")); assertEquals(gateway.uploads[0].first, gateway.uploads[1].first)
    }
    @Test fun retryAcrossMidnightPreservesDeadlineAndDate() {
        val settings = Settings(day); val gateway = Gateway().apply { failure = IOException() }
        var now = time("2026-10-15T23:55:00")
        fun run() = DriveExport(settings, gateway, { WalletState() }, { now }, zone).run()
        run(); now += 10 * 60 * 1000; gateway.failure = null; run()
        assertEquals(listOf(day, day.plusDays(1)), gateway.uploads.map { it.second })
        now += 5 * 60 * 1000; run()
        assertEquals(day, gateway.uploads[2].second)
    }
    @Test fun pauseAndAuthorizationStopNetworkUntilReconnect() {
        val settings = Settings(day); val gateway = Gateway()
        fun run() = DriveExport(settings, gateway, { WalletState() }, { time("2026-10-15T12:00:00") }, zone).run()
        settings.selected = settings.selected.copy(enabled = false); run(); assertEquals(0, gateway.calls)
        settings.selected = settings.selected.copy(enabled = true)
        gateway.failure = ExportFailure(false, true, "请重新授权")
        run(); assertTrue(settings.selected.needsAuthorization); assertTrue(gateway.invalidated)
        run(); assertEquals(1, gateway.calls)
    }
    @Test fun changedSnapshotReplacesSameDayFile() {
        val settings = Settings(day); val gateway = Gateway()
        var state = WalletState(listOf(Account(1, "银行", 0, 0)), listOf(entry(1, "2026-10-15T12:00:00")))
        fun run() = DriveExport(settings, gateway, { state }, { time("2026-10-15T12:00:00") }, zone).run()
        run(); state = state.copy(entries = emptyList()); run()
        assertEquals(gateway.uploads[0].first, gateway.uploads[1].first)
        assertFalse(gateway.uploads[1].third.contains("12.34"))
    }
}
