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
import io.openwallet.demo.export.*
import io.openwallet.demo.ui.DriveDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DriveUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "drive-ui-${java.util.UUID.randomUUID()}"
    private val settings = DriveSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    @After fun cleanup() {
        rule.runOnUiThread { rule.activity.setContent {} }
        assertTrue(context.deleteSharedPreferences(name))
    }
    @Test fun validationRetryAndPauseAreVisible() {
        rule.runOnUiThread { rule.activity.setContent { WalletTheme { DriveDialog(settings) {} } } }
        rule.onNodeWithText("连接并开启").assertIsNotEnabled()
        rule.onNodeWithTag("drive-email").performTextInput("bad-address")
        rule.onNodeWithText("连接并开启").assertIsNotEnabled()
        rule.onNodeWithTag("drive-email").performTextReplacement("wallet@example.com")
        rule.onNodeWithText("连接并开启").assertIsEnabled()
        screenshot("connect")
        val day = LocalDate.now()
        rule.runOnIdle {
            settings.connect("wallet@example.com")
            settings.saveQueue(DriveQueue(day, mapOf(day to DriveAttempt(6, Long.MAX_VALUE))))
            settings.failure("达到重试上限，请点立即重试")
        }
        rule.onNodeWithText("达到重试上限，请点立即重试").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("立即重试").performScrollTo().performClick()
        rule.waitUntil(10_000) { settings.queue().pending[day] == DriveAttempt() }
        rule.onNodeWithText("已安排上传，联网后执行").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("达到重试上限，请点立即重试").assertDoesNotExist()
        screenshot("connected")
        rule.onNodeWithText("暂停云盘导出").performScrollTo().performClick()
        rule.waitUntil(10_000) { !settings.target().enabled }
        rule.onNodeWithText("已暂停云盘导出").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("立即重试").assertDoesNotExist()
    }
    @Test fun unavailableServicesExplainHowToRecoverWithoutConnecting() {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        assumeTrue(status != ConnectionResult.SUCCESS)
        rule.runOnUiThread { rule.activity.setContent { WalletTheme { DriveDialog(settings) {} } } }
        rule.onNodeWithTag("drive-email").performTextInput("wallet@example.com")
        rule.onNodeWithText("连接并开启").performScrollTo().performClick()
        rule.onNodeWithTag("drive-error").performScrollTo().assertIsDisplayed().assertTextContains("服务码 $status", substring = true)
        assertFalse(settings.target().enabled); assertTrue(settings.target().email.isBlank())
        screenshot("services-unavailable")
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "drive-update").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
