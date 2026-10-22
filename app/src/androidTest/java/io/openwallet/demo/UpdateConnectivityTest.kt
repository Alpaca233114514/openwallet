package io.openwallet.demo

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.ui.SettingsScreen
import io.openwallet.demo.update.*
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in smoke check; normal tests remain independent of public-network availability. */
@RunWith(AndroidJUnit4::class)
class UpdateConnectivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun checkRealGitHubFromApp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdates") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val status = AtomicInteger()
        val checker = GitHubUpdateChecker {
            GitHubReleaseTransport().latest().also { status.set(it.status) }
        }
        val vm = UpdateViewModel(context.applicationContext as Application, checker)
        val started = System.currentTimeMillis()
        try {
            rule.runOnUiThread { rule.activity.setContent { WalletTheme { SettingsScreen({}, {}, {}, {}, vm) } } }
            rule.onNodeWithTag("check-updates").performScrollTo().performClick()
            rule.waitUntil(45_000) { vm.result.value != UpdateResult.Checking && vm.result.value != UpdateResult.Idle }
            rule.onNodeWithTag("check-updates").performScrollTo().assertIsEnabled()
            val result = vm.result.value
            val receipt = JSONObject()
                .put("endpoint", UpdateSource.API_URL)
                .put("httpStatus", status.get().takeIf { it != 0 } ?: JSONObject.NULL)
                .put("elapsedMs", System.currentTimeMillis() - started)
                .put("result", result.javaClass.simpleName)
                .put("message", (result as? UpdateResult.Failed)?.message ?: "")
            val folder = File(context.getExternalFilesDir(null), "settings-update").apply { mkdirs() }
            File(folder, "live-connectivity.json").writeText(receipt.toString(2))
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(folder, "live-connectivity.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertFalse("Real update check failed: $receipt", result is UpdateResult.Failed)
        } finally {
            rule.runOnUiThread { rule.activity.setContent {} }
        }
    }
}
