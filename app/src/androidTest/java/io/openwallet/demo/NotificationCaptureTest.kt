package io.openwallet.demo

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
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
    private val capture = NotificationCapture(store, prefs, bufferMillis = 0)
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
    private fun bankNotification(time: Long, id: Int = 23, amount: String = "23.00", direction: String = "消费") = StatusBarNotification(
        "cmb.pb", "cmb.pb", id, "transaction", 10001, 0, 0,
        Notification.Builder(context, "duplicate-test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("招商银行").setContentText("尾号1234${direction}人民币${amount}元").setWhen(time).build(),
        UserHandle.getUserHandleForUid(10001), time
    )
    private fun setupBank(): Account = setup().also { a ->
        assertTrue(prefs.edit().putBoolean("cmb.pb", true).putLong("account:cmb.pb", a.id).commit())
    }
    @Test fun wechatAndBankForOneExpenseAcrossAccountsOnlyDebitOnce() {
        val bank = setupBank()
        store.saveAccount("微信", 10000)
        val wechat = store.snapshot().accounts.last()
        assertTrue(prefs.edit().putBoolean("com.tencent.mm", true).putLong("account:com.tencent.mm", wechat.id).commit())
        assertEquals(CaptureResult.AUTO_POSTED, capture.receive(bankNotification(wechat.asOf + 1000)))
        assertEquals(CaptureResult.PENDING_REVIEW, capture.receive("com.tencent.mm", "微信支付", "支付成功23.00元", wechat.asOf + 2000, "wechat-same-expense"))
        val state = store.snapshot()
        assertEquals(1, state.entries.count { !it.pending })
        assertEquals(17700L, state.accounts.sumOf { it.balance })
        val candidate = state.entries.single { it.pending }
        assertTrue(runCatching { store.resolve(candidate.id, true, wechat.id) }.isFailure)
        assertEquals(17700L, store.snapshot().accounts.sumOf { it.balance })
    }
    @Test fun oneBankNotificationAndIdenticalCallbacksWriteOnlyOneEntry() {
        val a = setupBank(); val notification = bankNotification(a.asOf + 1000)
        assertEquals(CaptureResult.AUTO_POSTED, capture.receive(notification))
        assertEquals(1, store.snapshot().entries.size)
        assertEquals(7700L, store.snapshot().accounts.single().balance)
        repeat(3) { assertEquals(CaptureResult.DUPLICATE, capture.receive(notification)) }
        val persisted = WalletStore(context, "$name.db").use { it.snapshot() }
        assertEquals(1, persisted.entries.size)
        assertEquals(7700L, persisted.accounts.single().balance)
    }
    @Test fun updatedBankNotificationCannotBeConfirmedTwiceWithoutApproval() {
        val a = setupBank()
        val original = bankNotification(a.asOf + 1000)
        val updated = bankNotification(a.asOf + 2000)
        assertEquals(original.key, updated.key) // One visible Android notification, updated in place.
        assertEquals(CaptureResult.AUTO_POSTED, capture.receive(original))
        assertEquals(CaptureResult.PENDING_REVIEW, capture.receive(updated))
        assertEquals(7700L, store.snapshot().accounts.single().balance)
        val candidate = store.snapshot().entries.single { it.pending }
        val failure = runCatching { store.resolve(candidate.id, true, a.id) }.exceptionOrNull()
        assertNotNull("The same visible notification must not debit another 23 yuan on confirmation", failure)
        assertEquals("疑似重复：请核对后勾选仍然入账", failure?.message)
        assertEquals(7700L, store.snapshot().accounts.single().balance)
        store.resolve(candidate.id, false, a.id)
        assertEquals(CaptureResult.DUPLICATE, capture.receive(updated))
        assertEquals(1, store.snapshot().entries.size)
    }
    @Test fun sameBankNotificationUpdatedAfterTwoMinutesMustNotAutoDebitAgain() {
        val a = setupBank()
        val original = bankNotification(a.asOf + 1000)
        val updated = bankNotification(a.asOf + 181000)
        assertEquals(original.key, updated.key)
        assertEquals(CaptureResult.AUTO_POSTED, capture.receive(original))
        assertEquals(CaptureResult.PENDING_REVIEW, capture.receive(updated))
        assertEquals(7700L, store.snapshot().accounts.single().balance)
        assertTrue(runCatching { store.resolve(store.snapshot().entries.single { it.pending }.id, true, a.id) }.isFailure)
        assertEquals(7700L, store.snapshot().accounts.single().balance)
    }
    @Test fun separateRealExpensesCanStillBeExplicitlyApproved() {
        val a = setupBank()
        assertEquals(CaptureResult.AUTO_POSTED, capture.receive(bankNotification(a.asOf + 1000)))
        assertEquals(CaptureResult.PENDING_REVIEW, capture.receive(bankNotification(a.asOf + 2000, id = 24)))
        store.resolve(store.snapshot().entries.single { it.pending }.id, true, a.id, allowPossible = true)
        assertEquals(5400L, store.snapshot().accounts.single().balance)
        assertEquals(2, store.snapshot().entries.count { !it.pending })
    }
    @Test fun wechatFirstBankSecondAlsoOnlyDebitsOnce() {
        val bank = setupBank()
        store.saveAccount("微信", 10000)
        val wechat = store.snapshot().accounts.last()
        assertTrue(prefs.edit().putBoolean("com.tencent.mm", true).putLong("account:com.tencent.mm", wechat.id).commit())
        assertEquals(CaptureResult.AUTO_POSTED, capture.receive("com.tencent.mm", "微信支付", "支付成功23.00元", wechat.asOf + 1000, "wechat-first"))
        assertEquals(CaptureResult.PENDING_REVIEW, capture.receive(bankNotification(wechat.asOf + 2000)))
        val persisted = WalletStore(context, "$name.db").use { it.snapshot() }
        assertEquals(17700L, persisted.accounts.sumOf { it.balance })
        assertEquals(bank.balance, persisted.accounts.first { it.id == bank.id }.balance)
        assertEquals(1, persisted.entries.count { !it.pending })
    }
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
