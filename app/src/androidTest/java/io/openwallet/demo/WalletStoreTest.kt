package io.openwallet.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.domain.*
import org.junit.After
import org.junit.BeforeClass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "test-${java.util.UUID.randomUUID()}.db"
    private val store = WalletStore(context, databaseName)
    @After fun close() { store.close(); assertTrue(context.deleteDatabase(databaseName)) }
    companion object {
        @BeforeClass @JvmStatic fun cleanPreviousTestFixtures() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.databaseList().filter { Regex("(?:capture-test-|test-|ui-test-)[0-9a-f-]{36}\\.db").matches(it) }.forEach {
                assertTrue(context.deleteDatabase(it))
                if (it.startsWith("capture-test-")) context.deleteSharedPreferences(it.removeSuffix(".db"))
            }
            java.io.File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
                .filter { Regex("capture-test-[0-9a-f-]{36}\\.xml").matches(it.name) }
                .forEach { assertTrue(context.deleteSharedPreferences(it.name.removeSuffix(".xml"))) }
        }
    }
    private fun setup(): Account { store.saveAccount("测试账户", 100_00); return store.snapshot().accounts.single() }
    private fun payment(a: Account, time: Long = a.asOf + 1_000, external: String = "bank-1") = Entry(accountId = a.id, time = time, amount = 20_00, direction = Direction.EXPENSE, merchant = "测试咖啡", category = "餐饮", externalId = external)
    @Test fun historicalImportsDoNotChangeCurrentBalance() {
        val a = setup()
        store.insert(listOf(payment(a, a.asOf - 100_000)))
        assertEquals(100_00L, store.snapshot().accounts.single().balance)
        assertEquals(1, store.snapshot().entries.size)
        store.insert(listOf(payment(a, a.asOf + 1_000, "bank-2")))
        assertEquals(80_00L, store.snapshot().accounts.single().balance)
    }
    @Test fun repeatImportIsIdempotent() {
        val a = setup(); val e = payment(a)
        assertEquals(1, store.insert(listOf(e)))
        assertEquals(0, store.insert(listOf(e)))
        assertEquals(80_00L, store.snapshot().accounts.single().balance)
        assertEquals(Match.EXACT, store.preview(listOf(e)).single().match)
    }
    @Test fun conflictRollsBackWholeBatch() {
        val a = setup(); val e = payment(a); store.insert(listOf(e))
        val next = e.copy(time = e.time + 10_000, externalId = "bank-2")
        assertTrue(runCatching { store.insert(listOf(next, e.copy(amount = 999))) }.isFailure)
        assertEquals(1, store.snapshot().entries.size)
        assertEquals(80_00L, store.snapshot().accounts.single().balance)
    }
    @Test fun dismissedNotificationNeverChangesBalanceOrReappears() {
        val a = setup(); val e = payment(a).copy(pending = true, source = "通知")
        store.insert(listOf(e))
        assertEquals(100_00L, store.snapshot().accounts.single().balance)
        store.resolve(store.snapshot().entries.single().id, false, a.id)
        assertEquals(100_00L, store.snapshot().accounts.single().balance)
        assertEquals(0, store.insert(listOf(e)))
        assertTrue(store.snapshot().entries.isEmpty())
    }
    @Test fun confirmationPostsCandidateExactlyOnce() {
        val a = setup(); store.insert(listOf(payment(a).copy(pending = true, source = "通知")))
        val id = store.snapshot().entries.single().id
        store.resolve(id, true, a.id)
        assertEquals(80_00L, store.snapshot().accounts.single().balance)
        assertTrue(runCatching { store.resolve(id, true, a.id) }.isFailure)
        assertEquals(80_00L, store.snapshot().accounts.single().balance)
    }
    @Test fun newDuplicateAfterPreviewIsSkippedUnlessExplicitlyApproved() {
        val a = setup(); val e = payment(a).copy(externalId = "")
        assertEquals(Match.NEW, store.preview(listOf(e)).single().match)
        store.insert(listOf(e))
        assertEquals(0, store.insert(listOf(e)))
        assertEquals(1, store.insert(listOf(e), approvedPossible = setOf(e)))
        assertEquals(60_00L, store.snapshot().accounts.single().balance)
    }
}
