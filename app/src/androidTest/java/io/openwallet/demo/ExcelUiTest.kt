package io.openwallet.demo

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.data.WalletStore
import io.openwallet.demo.export.*
import io.openwallet.demo.ui.ExcelDialog
import io.openwallet.demo.ui.WalletApp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ExcelUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val unique = java.util.UUID.randomUUID().toString()
    private val prefsName = "excel-ui-$unique"
    private val databaseName = "excel-ui-$unique.db"
    private val settings = ExcelSettings(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
    private val models = ViewModelStore()
    private val store = WalletStore(context, databaseName)
    private var permission: Uri? = null
    private var activityClosed = false

    @After fun cleanup() {
        instrumentation.runOnMainSync { if (!activityClosed) rule.activity.setContent {}; models.clear() }
        store.close()
        assertTrue(context.deleteDatabase(databaseName))
        assertTrue(context.deleteSharedPreferences(prefsName))
        permission?.let { context.contentResolver.releasePersistableUriPermission(it,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    @Test fun localFolderPickerWritesRealWorkbookAndPauseSurvivesReopen() {
        store.seedDemo()
        lateinit var vm: WalletViewModel
        rule.runOnUiThread {
            vm = WalletViewModel(context.applicationContext as Application, store)
            models.put("ledger", vm)
            rule.activity.setContent { WalletTheme { WalletApp(vm) } }
        }
        rule.onNodeWithText("我的资产").assertIsDisplayed()
        screenshot("assets")
        rule.onNodeWithText("收支", useUnmergedTree = true).performClick()
        rule.onNodeWithText("收支明细").assertIsDisplayed()
        screenshot("transactions")
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("Excel 自动导出").assertIsDisplayed()
        rule.onNodeWithText("Google Sheets 自动导出").assertDoesNotExist()
        screenshot("settings")
        rule.onNodeWithText("Excel 自动导出").performClick()
        rule.onNodeWithText("选择文件夹并开启").assertIsDisplayed()
        screenshot("excel-choose")
        rule.onNodeWithText("完成").performClick()

        val folder = "OpenWallet-Excel-${unique.take(8)}"
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Documents/$folder").use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2F$folder")
        settings.connect(tree.toString(), folder); settings.pause()
        rule.runOnUiThread { rule.activity.setContent { WalletTheme { Box { WalletApp(vm); ExcelDialog(settings) {} } } } }
        rule.onNodeWithText("更换文件夹").performClick()
        clickSystemText("USE THIS FOLDER", "使用此文件夹")
        clickSystemText("ALLOW", "允许")
        rule.waitUntil(10_000) { settings.enabled }
        permission = tree
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission })
        val snapshot = store.snapshot()
        synchronized(ExcelSync.lock) {
            assertEquals(ExportResult.DONE, ExcelExport(context, settings).run { snapshot })
        }
        val file = settings.file
        assertTrue(file.endsWith(".xlsx"))
        val before = readSheet(file)
        assertTrue(before.contains("巷口咖啡"))
        // A changed snapshot rewrites the same file, including removing stale rows.
        synchronized(ExcelSync.lock) {
            assertEquals(ExportResult.DONE, ExcelExport(context, settings).run { snapshot.copy(entries = snapshot.entries.take(1)) })
        }
        assertEquals(file, settings.file)
        assertFalse(readSheet(file).contains("巷口咖啡"))
        synchronized(ExcelSync.lock) {
            assertEquals(ExportResult.DONE, ExcelExport(context, settings).run { snapshot })
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已导出 5 条流水").fetchSemanticsNodes().isNotEmpty() }
        screenshot("excel-connected")
        // Retain an exported synthetic fixture for an independent desktop reader.
        val output = File(context.getExternalFilesDir(null), "excel-export/sample.xlsx")
        context.contentResolver.openInputStream(Uri.parse(file))!!.use { input -> output.outputStream().use { input.copyTo(it) } }
        rule.onNodeWithText("暂停导出").performScrollTo().performClick()
        rule.waitUntil(10_000) { !settings.enabled }
        assertFalse(ExcelSettings(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)).enabled)
        assertEquals(ExportResult.STOPPED, ExcelExport(context, settings).run { error("paused export read snapshot") })
        rule.onNodeWithText("继续自动导出").assertIsDisplayed()
        screenshot("excel-paused")
        rule.onNodeWithText("继续自动导出").performClick()
        rule.waitUntil(10_000) { settings.enabled }
        rule.onNodeWithText("立即导出").assertIsDisplayed()
        context.contentResolver.releasePersistableUriPermission(tree,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        permission = null
        // The picker also granted temporary access for the Activity lifetime. End it before
        // checking the revoked persistent permission as a background worker would.
        activityClosed = true
        rule.activityRule.scenario.close()
        synchronized(ExcelSync.lock) {
            assertEquals(ExportResult.STOPPED, ExcelExport(context, settings).run { snapshot })
        }
        assertFalse(settings.enabled)
        assertTrue(settings.status.contains("权限已失效"))
    }

    private fun clickSystemText(vararg texts: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = texts.asSequence().flatMap { root?.findAccessibilityNodeInfosByText(it).orEmpty().asSequence() }
                .firstOrNull { candidate -> texts.any { it.equals(candidate.text?.toString(), ignoreCase = true) } }
            if (node != null) {
                var clickable = node
                while (!clickable!!.isClickable && clickable.parent != null) clickable = clickable.parent
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            Thread.sleep(250)
        }
        error("System picker action not found: ${texts.joinToString()}")
    }
    private fun readSheet(uri: String): String {
        ZipInputStream(context.contentResolver.openInputStream(Uri.parse(uri))!!).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "xl/worksheets/sheet1.xml") return zip.readBytes().toString(Charsets.UTF_8) }
        }
        error("Workbook missing worksheet")
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        // Accessibility/Compose can be idle one frame before SurfaceFlinger presents the new page.
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        val folder = File(context.getExternalFilesDir(null), "excel-export").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
