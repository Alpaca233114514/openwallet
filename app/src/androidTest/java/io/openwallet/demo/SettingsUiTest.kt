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
import io.openwallet.demo.ui.SettingsScreen
import io.openwallet.demo.ui.WalletApp
import io.openwallet.demo.update.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var store: WalletStore? = null
    private val databaseName = "settings-ui-${java.util.UUID.randomUUID()}.db"

    @After fun cleanup() {
        rule.runOnUiThread { rule.activity.setContent {}; models.clear() }
        store?.let { it.close(); assertTrue(context.deleteDatabase(databaseName)) }
    }

    @Test fun settingsOpensWithoutAccountsAndBothBackActionsReturnToLedger() {
        val ledger = WalletStore(context, databaseName).also { store = it }
        rule.runOnUiThread {
            val vm = WalletViewModel(context.applicationContext as Application, ledger)
            models.put("ledger", vm)
            rule.activity.setContent { WalletTheme { WalletApp(vm) } }
        }
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("settings-screen").assertIsDisplayed()
        screenshot("settings")
        rule.onNodeWithText("导入银行流水").assertIsDisplayed()
        rule.onNodeWithText("记一笔").assertDoesNotExist()
        rule.onNodeWithText("通知自动记账").performClick()
        rule.onNodeWithText("读取交易通知").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithContentDescription("返回账本").performClick()
        rule.onNodeWithText("添加第一个账户").assertIsDisplayed()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("settings-screen").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("添加第一个账户").assertIsDisplayed()
    }

    @Test fun updateCanRetryAndSurviveSettingsReentry() {
        val calls = AtomicInteger()
        val vm = UpdateViewModel(context.applicationContext as Application, UpdateChecker {
            when (calls.incrementAndGet()) {
                1 -> UpdateResult.Failed("连接超时，请检查网络后重试")
                2 -> UpdateResult.UpToDate
                else -> UpdateResult.Available(AppRelease("v0.2.0", "新增独立设置页与检查更新", "${UpdateSource.RELEASES_URL}/tag/v0.2.0", true))
            }
        })
        showSettings(vm)
        rule.onNodeWithTag("check-updates").performScrollTo().performClick()
        rule.waitUntil(5_000) { vm.result.value is UpdateResult.Failed }
        rule.onNodeWithText("连接超时，请检查网络后重试").performScrollTo().assertIsDisplayed()
        screenshot("network-error")
        rule.onNodeWithTag("check-updates").performScrollTo().performClick()
        rule.waitUntil(5_000) { vm.result.value == UpdateResult.UpToDate }
        rule.onNodeWithText("当前已是最新版本").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("check-updates").performClick()
        rule.waitUntil(5_000) { vm.result.value is UpdateResult.Available }
        rule.onNodeWithText("前往下载更新").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("发现新版本 v0.2.0").performScrollTo().assertIsDisplayed()
        screenshot("update-available")
        // Re-entering the settings composition retains the ViewModel's result without another request.
        rule.runOnUiThread { rule.activity.setContent {} }
        showSettings(vm)
        rule.onNodeWithText("发现新版本 v0.2.0").performScrollTo().assertIsDisplayed()
        assertEquals(3, calls.get())
    }

    @Test fun checkingDisablesDuplicateRequestsAndNoReleaseIsNotLatest() {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        val vm = UpdateViewModel(context.applicationContext as Application, UpdateChecker {
            calls.incrementAndGet()
            check(gate.await(10, TimeUnit.SECONDS))
            UpdateResult.NoPublicRelease
        })
        showSettings(vm)
        try {
            rule.onNodeWithTag("check-updates").performScrollTo().performClick()
            rule.waitUntil(5_000) { calls.get() == 1 }
            rule.onNodeWithTag("check-updates").assertIsNotEnabled()
            rule.runOnIdle { vm.check() }
            assertEquals(1, calls.get())
        } finally { gate.countDown() }
        rule.waitUntil(5_000) { vm.result.value == UpdateResult.NoPublicRelease }
        rule.onNodeWithText("暂未找到公开发布版本").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("当前已是最新版本").assertDoesNotExist()
        screenshot("no-public-release")
    }

    private fun showSettings(vm: UpdateViewModel) {
        rule.runOnUiThread {
            if (models["updates"] !== vm) models.put("updates", vm)
            rule.activity.setContent { WalletTheme { SettingsScreen({}, {}, {}, {}, {}, vm) } }
        }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "settings-update").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
