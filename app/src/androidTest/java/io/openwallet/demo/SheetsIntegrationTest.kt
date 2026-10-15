package io.openwallet.demo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.domain.*
import io.openwallet.demo.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SheetsIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun preferencesPersistPerAccountAndPauseWithoutLosingDestination() {
        val name = "sheets-test-${java.util.UUID.randomUUID()}"
        try {
            val settings = SheetsSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            assertFalse(settings.target().enabled)
            settings.connect("one@example.com"); settings.saveFile(SheetFile("one-file", 7)); settings.success(3)
            val key = settings.installKey
            settings.connect("two@example.com"); assertNull(settings.file()); assertEquals(0L, settings.lastSuccess)
            settings.saveFile(SheetFile("two-file", 8)); settings.pause()
            val restored = SheetsSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            assertFalse(restored.target().enabled); assertEquals(SheetFile("two-file", 8), restored.file())
            restored.connect("one@example.com"); assertEquals(SheetFile("one-file", 7), restored.file())
            assertTrue(restored.lastSuccess > 0); assertEquals(key, restored.installKey)
        } finally { assertTrue(context.deleteSharedPreferences(name)) }
    }
    @Test fun payloadClearsTrailingRowsAndWritesFormulaLikeTextLiterally() {
        val rows = listOf(SheetsRows.header, listOf("time", "account", "支出", "28.50", "=1+1", "餐饮", "000001", "通知", "12"))
        val requests = SheetsPayload.replace(9, rows, 1000, 26).getJSONArray("requests")
        assertEquals(1, requests.length())
        val update = requests.getJSONObject(0).getJSONObject("updateCells")
        assertEquals(1000, update.getJSONObject("range").getInt("endRowIndex"))
        assertEquals(9, update.getJSONObject("range").getInt("endColumnIndex"))
        val cells = update.getJSONArray("rows").getJSONObject(1).getJSONArray("values")
        assertEquals("=1+1", cells.getJSONObject(4).getJSONObject("userEnteredValue").getString("stringValue"))
        assertFalse(cells.getJSONObject(4).getJSONObject("userEnteredValue").has("formulaValue"))
        val empty = SheetsPayload.replace(9, listOf(SheetsRows.header), 1000, 26).getJSONArray("requests").getJSONObject(0).getJSONObject("updateCells")
        assertEquals(1, empty.getJSONArray("rows").length())
        assertEquals(1000, empty.getJSONObject("range").getInt("endRowIndex"))
    }
    @Test fun payloadGrowsGridBeforeWriting() {
        val requests = SheetsPayload.replace(42, List(5) { SheetsRows.header }, 2, 3).getJSONArray("requests")
        assertEquals(2, requests.length())
        val grid = requests.getJSONObject(0).getJSONObject("updateSheetProperties").getJSONObject("properties").getJSONObject("gridProperties")
        assertEquals(5, grid.getInt("rowCount")); assertEquals(9, grid.getInt("columnCount"))
    }
    @Test fun committedNotificationsAndRollbackTriggerCorrectExportSnapshots() {
        val name = "sheets-ledger-${java.util.UUID.randomUUID()}.db"
        var changes = 0
        val store = WalletStore(context, name, onChanged = { changes++ })
        try {
            store.saveAccount("账户", 10000); val id = store.snapshot().accounts.single().id
            val entry = Entry(accountId = id, time = 1000, amount = 1234, direction = Direction.EXPENSE, merchant = "咖啡", category = "餐饮", source = "通知", externalId = "notice-1")
            store.recordNotification(entry, true)
            assertEquals(2, changes); assertEquals(2, SheetsRows.from(store.snapshot()).size)
            val posted = store.snapshot().entries.single()
            store.moveNotificationToReview(posted.id)
            assertEquals(3, changes); assertEquals(1, SheetsRows.from(store.snapshot()).size)
            store.resolve(posted.id, true, id)
            assertEquals(4, changes); assertEquals(2, SheetsRows.from(store.snapshot()).size)
            try { store.insert(listOf(entry.copy(externalId = "new", time = 999999), entry.copy(amount = 2000)), allowPossible = true); fail("conflict must roll back") }
            catch (_: IllegalStateException) { }
            assertEquals(4, changes); assertEquals(1, store.snapshot().entries.size)
        } finally { store.close(); assertTrue(context.deleteDatabase(name)) }
    }
}
