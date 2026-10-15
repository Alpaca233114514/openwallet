package io.openwallet.demo

import android.graphics.Bitmap
import android.app.Application
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.ui.WalletApp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WalletUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "ui-test-${java.util.UUID.randomUUID()}.db"
    private val store = WalletStore(context, databaseName)
    private val models = ViewModelStore()
    @Before fun useIsolatedLedger() {
        rule.runOnUiThread {
            val vm = WalletViewModel(context.applicationContext as Application, store)
            models.put("isolated-ledger", vm)
            rule.activity.setContent { WalletTheme { WalletApp(vm) } }
        }
    }
    @After fun removeIsolatedLedger() {
        rule.runOnUiThread { rule.activity.setContent {}; models.clear() }
        store.close()
        org.junit.Assert.assertTrue(context.deleteDatabase(databaseName))
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(rule.activity.getExternalFilesDir(null), "notification-update").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun assetsTransactionsAndManualEntry() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("体验示例账本").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("我的资产").fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodesWithText("体验示例账本").fetchSemanticsNodes().isNotEmpty()) {
            screenshot("welcome")
            rule.onNodeWithText("体验示例账本").performClick()
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("我的资产").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已载入示例账本").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("招商银行").assertIsDisplayed()
        screenshot("assets")
        rule.onNodeWithText("收支", useUnmergedTree = true).performClick()
        rule.onNodeWithText("收支明细").assertIsDisplayed()
        screenshot("transactions")
        rule.onNodeWithText("记一笔", useUnmergedTree = true).performClick()
        rule.onNode(hasSetTextAction() and hasText("金额（元）")).performTextInput("12.34")
        rule.onNode(hasSetTextAction() and hasText("交易方 / 备注")).performTextInput("UI验收咖啡")
        rule.onNodeWithText("保存", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已记一笔").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("资产", useUnmergedTree = true).performClick()
        rule.onNodeWithText("我的资产").assertIsDisplayed()
        rule.onNodeWithContentDescription("账本工具").performClick()
        screenshot("tools")
        rule.onNodeWithText("通知自动记账").performClick()
        rule.onNodeWithTag("capture-enabled").performClick()
        rule.onNodeWithTag("capture-automatic").assertIsEnabled()
        rule.onNodeWithTag("capture-source:com.eg.android.AlipayGphone").performClick()
        rule.onNodeWithText("识别明确的交易直接入账，疑似重复待确认。").assertIsDisplayed()
        screenshot("capture")
        rule.onNodeWithText("取消").performClick()
    }
}
