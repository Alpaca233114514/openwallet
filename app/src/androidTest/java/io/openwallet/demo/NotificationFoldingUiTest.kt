package io.openwallet.demo

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.domain.*
import io.openwallet.demo.ui.WalletApp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NotificationFoldingUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "notification-folding-${java.util.UUID.randomUUID()}.db"
    private val store = WalletStore(context, name)
    private val models = ViewModelStore()
    private val groupLabel = "招商银行 / 微信 · 2 条通知 · 核对"

    @Before fun prepare() {
        store.saveAccount("招商银行", 10000)
        store.saveAccount("微信", 10000)
        val accounts = store.snapshot().accounts
        val time = System.currentTimeMillis() + 1000
        val bank = Entry(accountId = accounts[0].id, time = time, amount = 2300, direction = Direction.EXPENSE,
            merchant = "招商银行 交易", category = "待分类", source = "通知·招商银行", externalId = "bank-event")
        store.recordNotification(bank, autoPost = true)
        store.recordNotification(bank.copy(accountId = accounts[1].id, time = time + 1000,
            merchant = "微信 交易", source = "通知·微信", externalId = "wechat-event"), autoPost = true)
        rule.runOnUiThread {
            val vm = WalletViewModel(context.applicationContext as Application, store)
            models.put("folding", vm)
            rule.activity.setContent { WalletTheme { WalletApp(vm) } }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("我的资产").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("收支", useUnmergedTree = true).performClick()
    }

    @After fun cleanup() {
        rule.runOnUiThread { rule.activity.setContent {}; models.clear() }
        store.close()
        assertTrue(context.deleteDatabase(name))
    }

    @Test fun similarNotificationsRenderOneExpenseAndCanKeepOne() {
        rule.onNodeWithText(groupLabel).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("−23.00").assertCountEquals(1)
        rule.onNodeWithText("待确认通知 · 1").assertDoesNotExist()
        screenshot("folded")
        // Switching filters must not hide a candidate whose posted parent is absent.
        rule.onNodeWithText("收入", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("待确认 · 未入账").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("全部", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText(groupLabel).performScrollTo().performClick()
        rule.onNodeWithText("另记一笔").assertIsNotEnabled()
        rule.onNodeWithText("已入账：招商银行 · 通知·招商银行 · ¥ 23.00").assertIsDisplayed()
        screenshot("review")
        rule.onNodeWithText("保留一笔").performClick()
        rule.waitUntil(10_000) { store.snapshot().entries.none { it.pending } }
        assertEquals(17700L, store.snapshot().accounts.sumOf { it.balance })
        assertEquals(1, store.snapshot().entries.size)
        rule.onNodeWithText(groupLabel).assertDoesNotExist()
    }

    @Test fun twoRealExpensesRequireExplicitApproval() {
        rule.onNodeWithText(groupLabel).performScrollTo().performClick()
        rule.onNodeWithText("另记一笔").assertIsNotEnabled()
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("另记一笔").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { store.snapshot().entries.count { !it.pending } == 2 }
        assertEquals(15400L, store.snapshot().accounts.sumOf { it.balance })
    }

    @Test fun bufferedNotificationWaitsBeforeBecomingAnExpense() {
        val now = System.currentTimeMillis()
        val bank = store.snapshot().entries.single { !it.pending }
        store.recordNotification(bank.copy(id = 0, time = now + 1000, amount = 1700, externalId = "buffered-ui"), autoPost = true, postAt = now + 8000)
        rule.onNodeWithText("等待通知合并").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        screenshot("buffered")
        assertEquals(17700L, store.snapshot().accounts.sumOf { it.balance })
        store.flushBufferedNotifications(now + 8000)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("等待通知合并").fetchSemanticsNodes().isEmpty() }
        assertEquals(16000L, store.snapshot().accounts.sumOf { it.balance })
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "notification-folding").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
