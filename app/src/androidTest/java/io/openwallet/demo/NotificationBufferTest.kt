package io.openwallet.demo

import android.os.SystemClock
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
class NotificationBufferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "notification-buffer-${java.util.UUID.randomUUID()}"
    private val store = WalletStore(context, "$name.db")
    private val prefs = context.getSharedPreferences(name, 0)
    private fun setup(): Account {
        store.saveAccount("招商银行", 10000)
        val a = store.snapshot().accounts.single()
        assertTrue(prefs.edit().putBoolean("enabled", true).putBoolean("cmb.pb", true)
            .putBoolean("auto_post", true).putLong("account:cmb.pb", a.id).commit())
        return a
    }
    private fun receive(capture: NotificationCapture, a: Account) =
        capture.receive("cmb.pb", "招商银行", "消费人民币23.00元", a.asOf + 1000, "bank-buffered")

    @After fun close() {
        store.close()
        assertTrue(prefs.edit().clear().commit())
        assertTrue(context.deleteDatabase("$name.db"))
        assertTrue(context.deleteSharedPreferences(name))
    }

    @Test fun defaultBufferWaitsEightSecondsAndSurvivesReopening() {
        val a = setup(); val now = a.asOf + 10000
        val capture = NotificationCapture(store, prefs, now = { now })
        assertEquals(CaptureResult.BUFFERED, receive(capture, a))
        assertEquals(now + 8000, store.nextNotificationPostAt())
        assertEquals(10000L, store.snapshot().accounts.single().balance)
        assertEquals(0, store.flushBufferedNotifications(now + 7999))
        WalletStore(context, "$name.db").use { reopened ->
            assertEquals(now + 8000, reopened.nextNotificationPostAt())
            assertEquals(1, reopened.flushBufferedNotifications(now + 8000))
            assertEquals(7700L, reopened.snapshot().accounts.single().balance)
            assertNull(reopened.nextNotificationPostAt())
            assertEquals(0, reopened.flushBufferedNotifications(now + 16000))
        }
        assertEquals(CaptureResult.DUPLICATE, receive(capture, a))
    }

    @Test fun actualSchedulerFoldsWechatArrivingFiveSecondsLater() {
        val a = setup()
        store.saveAccount("微信", 10000)
        val wechat = store.snapshot().accounts.last()
        assertTrue(prefs.edit().putBoolean("com.tencent.mm", true).putLong("account:com.tencent.mm", wechat.id).commit())
        val capture = NotificationCapture(store, prefs)
        assertEquals(CaptureResult.BUFFERED, receive(capture, a))
        NotificationPoster(store).use { poster ->
            poster.schedule()
            SystemClock.sleep(5000)
            assertEquals(20000L, store.snapshot().accounts.sumOf { it.balance })
            assertEquals(CaptureResult.PENDING_REVIEW, capture.receive("com.tencent.mm", "微信支付", "支付成功23.00元",
                a.asOf + 6000, "wechat-five-seconds-later"))
            poster.schedule()
            val deadline = SystemClock.elapsedRealtime() + 12000
            while (store.snapshot().entries.none { !it.pending } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val state = store.snapshot()
            assertEquals(17700L, state.accounts.sumOf { it.balance })
            assertEquals(1, state.entries.count { !it.pending })
            assertEquals(1, state.entries.count { it.pending })
            assertEquals(1, LedgerCsv.parse(LedgerCsv.export(state.entries, state.accounts), state.accounts).size)
        }
    }

    @Test fun dismissingBufferedNotificationCancelsItsAutomaticPosting() {
        val a = setup(); val now = a.asOf + 10000
        receive(NotificationCapture(store, prefs, now = { now }), a)
        store.resolve(store.snapshot().entries.single().id, false, a.id)
        assertNull(store.nextNotificationPostAt())
        assertEquals(0, store.flushBufferedNotifications(now + 8000))
        assertEquals(10000L, store.snapshot().accounts.single().balance)
    }

    @Test fun finalCheckSeesPaymentPostedDuringBuffer() {
        val a = setup(); val now = a.asOf + 10000
        receive(NotificationCapture(store, prefs, now = { now }), a)
        val e = store.snapshot().entries.single()
        store.insert(listOf(e.copy(id = 0, source = "CSV", externalId = "bank-csv", pending = false, autoPostAt = 0)), allowPossible = true)
        assertEquals(0, store.flushBufferedNotifications(now + 8000))
        assertEquals(7700L, store.snapshot().accounts.single().balance)
        assertNull(store.nextNotificationPostAt())
    }

    @Test fun versionOneUpgradePreservesPostedPendingAndIgnoredHistory() {
        context.openOrCreateDatabase("$name.db", 0, null).use { db ->
            db.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, baseline INTEGER NOT NULL, as_of INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, occurred_at INTEGER NOT NULL, amount INTEGER NOT NULL, direction TEXT NOT NULL, merchant TEXT NOT NULL, category TEXT NOT NULL, source TEXT NOT NULL, external_id TEXT NOT NULL DEFAULT '', pending INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX external_identity ON entries(account_id, external_id) WHERE external_id <> ''")
            db.execSQL("INSERT INTO accounts VALUES(1,'招商银行',10000,0)")
            listOf(0, 1, 2).forEach { status ->
                db.execSQL("INSERT INTO entries VALUES(?,1,1000,2300,'EXPENSE','招商银行 交易','待分类','通知·招商银行',?,?)", arrayOf(status + 1, "old-$status", status))
            }
            db.version = 1
        }
        val state = store.snapshot()
        assertEquals(7700L, state.accounts.single().balance)
        assertEquals(2, state.entries.size)
        assertTrue(state.entries.all { it.autoPostAt == 0L })
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
        assertEquals(2, store.readableDatabase.version)
    }
}
