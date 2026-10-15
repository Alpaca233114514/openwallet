package io.openwallet.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.capture.*
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationCaptureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "capture-test-${java.util.UUID.randomUUID()}"
    private val store = WalletStore(context, "$name.db")
    private val prefs = context.getSharedPreferences(name, 0)
    private val capture = NotificationCapture(store, prefs)
    private val pkg = "com.eg.android.AlipayGphone"
    @After fun close() {
        store.close()
        // Wait for earlier apply() writes before deleting this test's preferences file.
        assertTrue(prefs.edit().clear().commit())
        assertTrue(context.deleteDatabase("$name.db"))
        assertTrue(context.deleteSharedPreferences(name))
    }
    private fun setup(): Account {
        store.saveAccount("通知账户", 10000)
        val a = store.snapshot().accounts.single()
        prefs.edit().putBoolean("enabled", true).putBoolean(pkg, true).putBoolean("auto_post", true).putLong("account:$pkg", a.id).commit()
        return a
    }
    private fun receive(a: Account, key: String = "event-1", time: Long = a.asOf + 1000, title: String = "付款成功") =
        capture.receive(pkg, title, "付款成功28.50元 商户：测试咖啡", time, key)
    @Test fun automaticPaymentPersistsOnceAndCanBeReversed() {
        val a = setup()
        assertEquals(CaptureResult.AUTO_POSTED, receive(a))
        assertEquals(7150L, store.snapshot().accounts.single().balance)
        assertFalse(store.snapshot().entries.single().pending)
        assertEquals(CaptureResult.DUPLICATE, receive(a))
        val persisted = WalletStore(context, "$name.db").use { it.snapshot() }
        assertEquals(7150L, persisted.accounts.single().balance)
        val id = persisted.entries.single().id
        store.moveNotificationToReview(id)
        assertEquals(10000L, store.snapshot().accounts.single().balance)
        store.resolve(id, false, a.id)
        assertEquals(CaptureResult.DUPLICATE, receive(a))
        assertTrue(store.snapshot().entries.isEmpty())
    }
    @Test fun updatesAndCrossSourceMatchesStayPending() {
        val a = setup()
        receive(a)
        assertEquals(CaptureResult.PENDING_REVIEW, receive(a, "event-update", a.asOf + 2000))
        assertEquals(7150L, store.snapshot().accounts.single().balance)
        val b = a.copy(asOf = a.asOf + 600000)
        store.insert(listOf(Entry(accountId = a.id, time = b.asOf, amount = 2850, direction = Direction.EXPENSE, merchant = "其他名称", category = "其他", source = "CSV", externalId = "bank-new")))
        assertEquals(CaptureResult.PENDING_REVIEW, receive(b, "event-2"))
        assertEquals(4300L, store.snapshot().accounts.single().balance)
    }
    @Test fun modesAndSourceSelectionAreRespected() {
        val a = setup()
        prefs.edit().putBoolean("enabled", false).commit()
        assertNull(receive(a))
        prefs.edit().putBoolean("enabled", true).putBoolean(pkg, false).commit()
        assertNull(receive(a))
        prefs.edit().putBoolean(pkg, true).putBoolean("auto_post", false).commit()
        assertEquals(CaptureResult.PENDING_REVIEW, receive(a))
        assertEquals(10000L, store.snapshot().accounts.single().balance)
        val id = store.snapshot().entries.single().id
        store.resolve(id, true, a.id)
        assertEquals(7150L, store.snapshot().accounts.single().balance)
    }
    @Test fun mappingChangesDoNotReplayAndUnknownTitlesNeedReview() {
        val a = setup(); receive(a)
        store.saveAccount("另一账户", 20000)
        val b = store.snapshot().accounts.last()
        prefs.edit().putLong("account:$pkg", b.id).commit()
        assertEquals(CaptureResult.DUPLICATE, receive(a))
        assertEquals(CaptureResult.PENDING_REVIEW, receive(b, "untrusted", title = "提醒"))
        assertEquals(20000L, store.snapshot().accounts.last().balance)
        assertEquals(CaptureResult.AUTO_POSTED, receive(b, "new-payment", b.asOf + 600000))
        assertEquals(17150L, store.snapshot().accounts.last().balance)
        prefs.edit().putLong("account:$pkg", -1).commit()
        assertNull(receive(b, "invalid-account"))
    }
}
