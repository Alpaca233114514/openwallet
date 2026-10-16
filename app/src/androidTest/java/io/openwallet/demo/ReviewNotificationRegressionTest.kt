package io.openwallet.demo

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.capture.NotificationCapture
import io.openwallet.demo.capture.CaptureResult
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent review reproductions. Synthetic data in fresh, disposable databases only. */
@RunWith(AndroidJUnit4::class)
class ReviewNotificationRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bank(time: Long) = StatusBarNotification(
        "cmb.pb", "cmb.pb", 23, "transaction", 10001, 0, 0,
        Notification.Builder(context, "review").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("\u62db\u5546\u94f6\u884c")
            .setContentText("\u5c3e\u53f71234\u6d88\u8d39\u4eba\u6c11\u5e0123.00\u5143")
            .setWhen(time).build(), UserHandle.getUserHandleForUid(10001), time)

    private fun fixture(block: (WalletStore, android.content.SharedPreferences, Long) -> Unit) {
        val name = "independent-review-${java.util.UUID.randomUUID()}"
        val store = WalletStore(context, "$name.db")
        val prefs = context.getSharedPreferences(name, 0)
        try {
            store.saveAccount("Review bank A", 10000)
            store.saveAccount("Review bank B", 10000)
            val first = store.snapshot().accounts.first()
            check(prefs.edit().putBoolean("enabled", true).putBoolean("auto_post", true)
                .putBoolean("cmb.pb", true).putLong("account:cmb.pb", first.id).commit())
            block(store, prefs, store.snapshot().accounts.last().asOf + 1000)
        } finally {
            store.close()
            context.deleteDatabase("$name.db")
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun remappingMustNotAutoPostAnUpdatedVisibleNotificationTwice() = fixture { store, prefs, time ->
        val capture = NotificationCapture(store, prefs, now = { time })
        val original = bank(time)
        val update = bank(time + 180000)
        assertEquals(original.key, update.key)
        capture.receive(original)
        assertEquals(1, store.flushBufferedNotifications(time + 8000))
        val second = store.snapshot().accounts.last()
        check(prefs.edit().putLong("account:cmb.pb", second.id).commit())
        val result = capture.receive(update)
        assertEquals(CaptureResult.PENDING_REVIEW, result)
        assertEquals(0, store.flushBufferedNotifications(time + 188000))
        val state = store.snapshot()
        println("REVIEW remap result=$result posted=${state.entries.count { !it.pending }} balance=${state.accounts.sumOf { it.balance }}")
        assertEquals("Same visible notification must only debit once after remapping", 1, state.entries.count { !it.pending })
        assertEquals(17700L, state.accounts.sumOf { it.balance })
        val candidate = state.entries.single { it.pending }
        assertEquals(0L, candidate.autoPostAt)
        assertEquals(second.id, candidate.accountId)
        assertNotNull(Duplicates.relatedPostedNotification(candidate, state.entries))
        assertTrue(runCatching { store.resolve(candidate.id, true, second.id) }.isFailure)
        assertEquals(17700L, store.snapshot().accounts.sumOf { it.balance })
        // A reused Android slot can still represent a real new payment, with explicit approval.
        store.resolve(candidate.id, true, second.id, allowPossible = true)
        assertEquals(15400L, store.snapshot().accounts.sumOf { it.balance })
        assertEquals(CaptureResult.DUPLICATE, capture.receive(update))
    }

    @Test fun ignoredNotificationUpdateMustRemainReviewable() = fixture { store, prefs, time ->
        val capture = NotificationCapture(store, prefs, now = { time })
        val original = bank(time)
        val update = bank(time + 180000)
        assertEquals(original.key, update.key)
        check(prefs.edit().putBoolean("auto_post", false).commit())
        capture.receive(original)
        val candidate = store.snapshot().entries.single()
        store.resolve(candidate.id, false, candidate.accountId)
        check(prefs.edit().putBoolean("auto_post", true).commit())
        val result = capture.receive(update)
        assertEquals(CaptureResult.PENDING_REVIEW, result)
        assertEquals(0, store.flushBufferedNotifications(time + 188000))
        val state = store.snapshot()
        println("REVIEW ignored result=$result posted=${state.entries.count { !it.pending }} balance=${state.accounts.sumOf { it.balance }}")
        assertEquals("An update of an explicitly ignored notification must not automatically debit", 0, state.entries.count { !it.pending })
        assertEquals(20000L, state.accounts.sumOf { it.balance })
        assertEquals(0L, state.entries.single().autoPostAt)
        assertEquals(CaptureResult.DUPLICATE, capture.receive(update))
    }

    @Test fun ignoredHistorySurvivesReopeningAndRemapping() = fixture { store, prefs, time ->
        check(prefs.edit().putBoolean("auto_post", false).commit())
        NotificationCapture(store, prefs).receive(bank(time))
        val ignored = store.snapshot().entries.single()
        store.resolve(ignored.id, false, ignored.accountId)
        val second = store.snapshot().accounts.last()
        check(prefs.edit().putBoolean("auto_post", true).putLong("account:cmb.pb", second.id).commit())
        WalletStore(context, java.io.File(store.readableDatabase.path).name).use { reopened ->
            val capture = NotificationCapture(reopened, prefs, now = { time + 180000 })
            assertEquals(CaptureResult.PENDING_REVIEW, capture.receive(bank(time + 180000)))
            assertEquals(0, reopened.flushBufferedNotifications(time + 188000))
            assertEquals(20000L, reopened.snapshot().accounts.sumOf { it.balance })
            assertTrue(reopened.snapshot().entries.single().pending)
            assertEquals(second.id, reopened.snapshot().entries.single().accountId)
        }
    }

    @Test fun remappingDuringBufferStillPostsOnlyOnce() = fixture { store, prefs, time ->
        val capture = NotificationCapture(store, prefs, now = { time })
        assertEquals(CaptureResult.BUFFERED, capture.receive(bank(time)))
        val second = store.snapshot().accounts.last()
        check(prefs.edit().putLong("account:cmb.pb", second.id).commit())
        assertEquals(CaptureResult.PENDING_REVIEW, capture.receive(bank(time + 180000)))
        assertEquals(1, store.flushBufferedNotifications(time + 188000))
        assertEquals(17700L, store.snapshot().accounts.sumOf { it.balance })
        assertEquals(1, store.snapshot().entries.count { it.pending })
    }

    @Test fun finalBufferCheckIncludesIgnoredHistoryFromOlderVersions() = fixture { store, prefs, time ->
        val capture = NotificationCapture(store, prefs, now = { time })
        assertEquals(CaptureResult.BUFFERED, capture.receive(bank(time)))
        val original = store.snapshot().entries.single()
        store.resolve(original.id, false, original.accountId)
        val updatedTime = time + 180000
        // Model a buffer persisted before the fix, with an updated timestamp and account mapping.
        val legacyBuffer = original.copy(id = 0, accountId = store.snapshot().accounts.last().id,
            time = updatedTime, externalId = original.externalId.removeSuffix(":$time") + ":$updatedTime",
            autoPostAt = updatedTime + 8000)
        assertEquals(1, store.insert(listOf(legacyBuffer)))
        assertEquals(0, store.flushBufferedNotifications(updatedTime + 8000))
        assertNull(store.nextNotificationPostAt())
        val state = store.snapshot()
        assertEquals(20000L, state.accounts.sumOf { it.balance })
        assertTrue(state.entries.single().pending)
    }
}
