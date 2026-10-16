package io.openwallet.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.openwallet.demo.update.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(AndroidJUnit4::class)
class UpdateCheckTest {
    private fun release(tag: String = "v0.2.0", apk: Boolean = true, prerelease: Boolean = false) = """
        {"tag_name":"$tag","draft":false,"prerelease":$prerelease,"body":"新增独立设置页",
         "html_url":"https://untrusted.example/ignored",
         "assets":[${if (apk) """{"name":"openwallet.apk","state":"uploaded"}""" else ""}]}
    """.trimIndent()
    private fun check(response: ReleaseResponse, current: String = "0.1.0-demo") =
        GitHubUpdateChecker { response }.check(current)

    @Test fun newerReleaseHasNotesAndFixedRepositoryLink() {
        val found = check(ReleaseResponse(200, release())) as UpdateResult.Available
        assertEquals("v0.2.0", found.release.version)
        assertEquals("新增独立设置页", found.release.notes)
        assertEquals("${UpdateSource.RELEASES_URL}/tag/v0.2.0", found.release.pageUrl)
        assertTrue(found.release.hasApk)
    }
    @Test fun sameAndOlderVersionsDoNotPromptAnUpdate() {
        assertEquals(UpdateResult.UpToDate, check(ReleaseResponse(200, release("v0.1.0")), "0.1.0"))
        assertEquals(UpdateResult.UpToDate, check(ReleaseResponse(200, release("v0.0.9"))))
    }
    @Test fun missingAssetsAndPrereleasesAreExplained() {
        assertFalse((check(ReleaseResponse(200, release(apk = false))) as UpdateResult.Available).release.hasApk)
        assertEquals(UpdateResult.NoPublicRelease, check(ReleaseResponse(200, release(prerelease = true))))
        assertEquals(UpdateResult.NoPublicRelease, check(ReleaseResponse(404)))
        val noNotes = release().replace("\"body\":\"新增独立设置页\"", "\"body\":null")
        assertEquals("", (check(ReleaseResponse(200, noNotes)) as UpdateResult.Available).release.notes)
    }
    @Test fun failuresNeverBecomeUpToDate() {
        listOf(403, 429, 503, 401, 302).forEach { assertTrue(check(ReleaseResponse(it)) is UpdateResult.Failed) }
        assertTrue(check(ReleaseResponse(200, "bad-json")) is UpdateResult.Failed)
        assertTrue(check(ReleaseResponse(200, release("latest"))) is UpdateResult.Failed)
        assertTrue(check(ReleaseResponse(200, release()), "unknown") is UpdateResult.Failed)
        assertEquals(UpdateResult.Failed("连接超时，请检查网络后重试"), GitHubUpdateChecker { throw SocketTimeoutException() }.check("0.1.0"))
        assertEquals(UpdateResult.Failed("无法连接更新服务，请检查网络后重试"), GitHubUpdateChecker { throw UnknownHostException() }.check("0.1.0"))
    }
}
