package io.openwallet.demo

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.ui.WalletApp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class WalletMotionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "motion-${java.util.UUID.randomUUID()}.db"
    private val store = WalletStore(context, databaseName)
    private val models = ViewModelStore()

    @Before fun setup() {
        rule.runOnUiThread {
            val vm = WalletViewModel(context.applicationContext as Application, store)
            models.put("motion-ledger", vm)
            rule.activity.setContent { WalletTheme { WalletApp(vm) } }
        }
    }

    @After fun cleanup() {
        rule.runOnUiThread { rule.activity.setContent {}; models.clear() }
        store.close()
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test fun bothEdgesPreviewCancelAndCommitWithoutExitingActivity() {
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("settings-screen").assertIsDisplayed()
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            start(edge)
            progress(0.15f, edge)
            // Both destinations are composed during preview, before navigation is committed.
            rule.onNodeWithText("添加第一个账户").assertExists()
            rule.onNodeWithTag("settings-screen").assertExists()
            progress(0.7f, edge)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            rule.waitForIdle()
            rule.onNodeWithTag("settings-screen").assertIsDisplayed()
            rule.onNodeWithText("添加第一个账户").assertDoesNotExist()
        }
        start(BackEventCompat.EDGE_RIGHT)
        progress(0.65f, BackEventCompat.EDGE_RIGHT)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("添加第一个账户").assertIsDisplayed()
        rule.onNodeWithTag("settings-screen").assertDoesNotExist()
        rule.runOnIdle {
            assertFalse(rule.activity.isFinishing)
            assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }

    @Test fun dialogBackKeepsSettingsAndNextBackReturnsToLedger() {
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("导入银行流水").performClick()
        rule.onNodeWithText("取消").assertIsDisplayed()
        pressBack()
        rule.onNodeWithText("取消").assertDoesNotExist()
        rule.onNodeWithTag("settings-screen").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("添加第一个账户").assertIsDisplayed()
    }

    @Test fun tabAndMonthSurviveTabSwitchAndSettingsReturn() {
        rule.onNodeWithText("体验示例账本").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("我的资产").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("收支", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("上个月").performClick()
        val month = YearMonth.now().minusMonths(1)
        val monthText = "${month.year} 年 ${month.monthValue} 月"
        rule.onNodeWithText(monthText).assertIsDisplayed()
        rule.onNodeWithText("资产", useUnmergedTree = true).performClick()
        rule.onNodeWithText("收支", useUnmergedTree = true).performClick()
        rule.onNodeWithText(monthText).assertIsDisplayed()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithContentDescription("返回账本").performClick()
        rule.onNodeWithText(monthText).assertIsDisplayed()
    }

    @Test fun settingsStateSurvivesSavedStateRestoreAndButtonBack() {
        // Save/restore the real Compose navigation state without touching the user's ledger.
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            WalletTheme {
                var open by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(true)
                }
                io.openwallet.demo.ui.WalletNavigation(open, true, { open = false }) {
                    androidx.compose.material3.Text(if (it) "restored-settings" else "restored-ledger")
                }
            }
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("restored-settings").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("restored-ledger").assertIsDisplayed()
    }

    private fun start(edge: Int) {
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, edge))
        }
        rule.waitForIdle()
    }

    private fun progress(value: Float, edge: Int) {
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 300f, value, edge))
        }
        rule.waitForIdle()
    }
}
