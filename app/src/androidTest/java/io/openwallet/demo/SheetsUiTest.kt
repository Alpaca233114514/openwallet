package io.openwallet.demo

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.openwallet.demo.export.SheetsSettings
import io.openwallet.demo.ui.SheetsDialog
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SheetsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "sheets-ui-${java.util.UUID.randomUUID()}"
    private val settings = SheetsSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    @After fun cleanup() {
        rule.runOnUiThread { rule.activity.setContent {} }
        assertTrue(context.deleteSharedPreferences(name))
    }
    @Test fun targetEmailValidationAndPauseAreVisible() {
        rule.runOnUiThread { rule.activity.setContent { WalletTheme { SheetsDialog(settings) {} } } }
        rule.onNodeWithText("连接并开启").assertIsNotEnabled()
        rule.onNodeWithTag("sheets-email").performTextInput("bad-address")
        rule.onNodeWithText("连接并开启").assertIsNotEnabled()
        rule.onNodeWithTag("sheets-email").performTextReplacement("wallet@example.com")
        rule.onNodeWithText("连接并开启").assertIsEnabled()
        screenshot("connect")
        rule.runOnIdle { settings.connect("wallet@example.com"); settings.success(5) }
        rule.onNodeWithText("当前账户：wallet@example.com").assertIsDisplayed()
        rule.onNodeWithText("已导出 5 条流水").assertIsDisplayed()
        screenshot("connected")
        rule.onNodeWithText("暂停导出").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已暂停自动导出").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("立即导出").assertDoesNotExist()
        rule.onNodeWithText("授权并启用").assertIsEnabled()
    }
    @Test fun missingGoogleServicesShowsActionableError() {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        assumeTrue("Requires a device without usable Google Play services", status != ConnectionResult.SUCCESS)
        rule.runOnUiThread { rule.activity.setContent { WalletTheme { SheetsDialog(settings) {} } } }
        rule.onNodeWithTag("sheets-email").performTextInput("wallet@example.com")
        rule.onNodeWithText("连接并开启").performClick()
        rule.onNodeWithTag("sheets-error").performScrollTo().assertIsDisplayed()
            .assertTextContains("服务码 $status", substring = true)
        rule.onNodeWithText("已取消授权").assertDoesNotExist()
        rule.onNodeWithText("连接并开启").assertIsEnabled()
        assertTrue(settings.target().email.isBlank())
        screenshot("services-unavailable")
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "sheets-update").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
