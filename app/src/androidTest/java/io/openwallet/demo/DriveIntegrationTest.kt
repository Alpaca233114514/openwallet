package io.openwallet.demo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.openwallet.demo.export.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DriveIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = LocalDate.of(2026, 10, 15)
    @Test fun retryStateAndDestinationsSurviveRestartAndAccountSwitch() {
        val name = "drive-test-${java.util.UUID.randomUUID()}"
        try {
            fun settings() = DriveSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            val s = settings(); assertFalse(s.target().enabled)
            s.connect("one@example.com"); s.saveFolderId("folder-one"); s.saveFileId(day, "csv-one")
            s.saveQueue(DriveQueue(day, mapOf(day to DriveAttempt(3, 123456789L))))
            s.pause()
            val restored = settings()
            assertFalse(restored.target().enabled); assertEquals("folder-one", restored.folderId())
            assertEquals(DriveAttempt(3, 123456789L), restored.queue().pending[day])
            restored.connect("two@example.com"); assertNull(restored.folderId()); assertNull(restored.fileId(day))
            restored.saveFolderId("folder-two"); restored.saveFileId(day, "csv-two")
            restored.connect("one@example.com")
            assertEquals("folder-one", restored.folderId()); assertEquals("csv-one", restored.fileId(day))
            assertEquals(DriveAttempt(), restored.queue().pending[day])
            assertEquals(s.installKey, restored.installKey)
        } finally { assertTrue(context.deleteSharedPreferences(name)) }
    }

    private fun metadata(id: String, kind: String, folder: String? = null) = JSONObject().put("id", id)
        .put("mimeType", if (folder == null) "application/vnd.google-apps.folder" else "text/csv")
        .put("ownedByMe", true).put("trashed", false)
        .put("appProperties", JSONObject().put("openwalletCsv", "install").put("day", kind))
        .put("parents", JSONArray().also { if (folder != null) it.put(folder) })

    @Test fun lostCreateResponseRecoversSameReservedIdAndUpdatesCsv() {
        var remote: JSONObject? = null
        var creates = 0; var updates = 0
        var content = ""
        val gateway = GoogleDriveGateway(context, "install", GoogleTransport { method, url, _, body, type ->
            when (method) {
                "GET" -> remote ?: throw ExportFailure(false, false, "missing", 404)
                "POST" -> {
                    creates++
                    assertTrue(type.startsWith("multipart/related; boundary="))
                    val sent = body!!.toString(Charsets.UTF_8)
                    assertTrue(sent.contains("\"id\":\"reserved\"")); assertTrue(sent.contains("openwallet-$day.csv"))
                    assertTrue(sent.contains("\"parents\":[\"folder\"]")); assertTrue(sent.contains("\uFEFFheader\r\nold\r\n"))
                    remote = metadata("reserved", day.toString(), "folder")
                    throw IOException("response lost")
                }
                "PATCH" -> {
                    assertTrue(url.contains("/reserved?uploadType=media")); assertEquals("text/csv; charset=UTF-8", type)
                    updates++; content = body!!.toString(Charsets.UTF_8); JSONObject().put("id", "reserved")
                }
                else -> error("unexpected method")
            }
        })
        try { gateway.upload("test-token", "folder", "reserved", day, "\uFEFFheader\r\nold\r\n"); fail() } catch (_: IOException) { }
        gateway.upload("test-token", "folder", "reserved", day, "\uFEFFheader\r\nnew\r\n")
        assertEquals(1, creates); assertEquals(1, updates); assertEquals("\uFEFFheader\r\nnew\r\n", content)
    }

    @Test fun conflictAfterCreateIsVerifiedBeforeUpdating() {
        var gets = 0; var updates = 0
        val gateway = GoogleDriveGateway(context, "install", GoogleTransport { method, _, _, _, _ ->
            when (method) {
                "GET" -> if (++gets == 1) throw ExportFailure(false, false, "missing", 404) else metadata("reserved", day.toString(), "folder")
                "POST" -> throw ExportFailure(false, false, "exists", 409)
                "PATCH" -> { updates++; JSONObject() }
                else -> error("unexpected")
            }
        })
        gateway.upload("test-token", "folder", "reserved", day, "csv")
        assertEquals(2, gets); assertEquals(1, updates)
    }

    @Test fun unrelatedOrMovedFilesAreNeverOverwritten() {
        for (bad in listOf(metadata("id", day.toString(), "other-folder"), metadata("id", day.toString(), "folder").put("ownedByMe", false),
            metadata("id", day.toString(), "folder").put("trashed", true), metadata("id", "other-day", "folder"))) {
            var writes = 0
            val gateway = GoogleDriveGateway(context, "install", GoogleTransport { method, _, _, _, _ ->
                if (method != "GET") writes++
                bad
            })
            try { gateway.upload("test-token", "folder", "id", day, "csv"); fail() } catch (e: ExportFailure) { assertFalse(e.retryable) }
            assertEquals(0, writes)
        }
    }

    @Test fun folderCreationUsesReservedIdAndRecoversConflict() {
        var file: JSONObject? = null; var creates = 0
        val gateway = GoogleDriveGateway(context, "install", GoogleTransport { method, url, _, body, _ ->
            when {
                url.contains("generateIds") -> JSONObject().put("ids", JSONArray().put("folder-id"))
                method == "GET" -> file ?: throw ExportFailure(false, false, "missing", 404)
                method == "POST" -> {
                    creates++; val sent = JSONObject(body!!.toString(Charsets.UTF_8))
                    assertEquals("folder-id", sent.getString("id")); assertEquals("OpenWallet 每日 CSV", sent.getString("name"))
                    file = metadata("folder-id", "folder")
                    throw ExportFailure(false, false, "exists", 409)
                }
                else -> error("unexpected")
            }
        })
        val id = gateway.reserveId("test-token")
        gateway.ensureFolder("test-token", id); gateway.ensureFolder("test-token", id)
        assertEquals(1, creates)
    }
}
