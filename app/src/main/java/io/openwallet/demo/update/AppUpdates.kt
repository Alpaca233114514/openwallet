package io.openwallet.demo.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

object UpdateSource {
    const val REPOSITORY = "Alpaca233114514/openwallet"
    const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
}

data class AppRelease(val version: String, val notes: String, val pageUrl: String, val hasApk: Boolean)

sealed interface UpdateResult {
    data object Idle : UpdateResult
    data object Checking : UpdateResult
    data object UpToDate : UpdateResult
    data object NoPublicRelease : UpdateResult
    data class Available(val release: AppRelease) : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

fun interface UpdateChecker {
    /** Blocking network call; invoked on Dispatchers.IO. */
    fun check(currentVersion: String): UpdateResult
}

data class ReleaseResponse(val status: Int, val body: String = "")
fun interface ReleaseTransport { fun latest(): ReleaseResponse }

class GitHubReleaseTransport : ReleaseTransport {
    override fun latest(): ReleaseResponse {
        val connection = URL(UpdateSource.API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "OpenWallet-Android")
            val status = connection.responseCode
            val body = if (status == 200) connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder(); val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 1_000_000) throw IOException("Release response too large")
                    text.append(buffer, 0, count)
                }
                text.toString()
            } else ""
            return ReleaseResponse(status, body)
        } finally { connection.disconnect() }
    }
}

class GitHubUpdateChecker(private val transport: ReleaseTransport = GitHubReleaseTransport()) : UpdateChecker {
    override fun check(currentVersion: String): UpdateResult = try {
        val response = transport.latest()
        when (response.status) {
            200 -> parseRelease(response.body, currentVersion)
            404 -> UpdateResult.NoPublicRelease
            403, 429 -> UpdateResult.Failed("GitHub 暂时限制访问，请稍后重试")
            in 500..599 -> UpdateResult.Failed("更新服务暂不可用，请稍后重试")
            else -> UpdateResult.Failed("检查更新失败（${response.status}），请稍后重试")
        }
    } catch (_: SocketTimeoutException) {
        UpdateResult.Failed("连接超时，请检查网络后重试")
    } catch (_: IOException) {
        UpdateResult.Failed("无法连接更新服务，请检查网络后重试")
    } catch (_: org.json.JSONException) {
        UpdateResult.Failed("更新信息格式无效，请前往发布页查看")
    }

    private fun parseRelease(body: String, currentVersion: String): UpdateResult {
        val json = JSONObject(body)
        if (json.getBoolean("draft") || json.getBoolean("prerelease")) return UpdateResult.NoPublicRelease
        val tag = json.getString("tag_name")
        val latest = ReleaseVersion.parse(tag)
            ?: return UpdateResult.Failed("发布版本号无法识别，请前往发布页查看")
        val current = ReleaseVersion.parse(currentVersion)
            ?: return UpdateResult.Failed("当前版本号无法比较，请前往发布页查看")
        if (latest <= current) return UpdateResult.UpToDate
        val assets = json.getJSONArray("assets")
        val hasApk = (0 until assets.length()).any {
            val asset = assets.getJSONObject(it)
            asset.optString("name").endsWith(".apk", ignoreCase = true) && asset.optString("state") == "uploaded"
        }
        // Construct the link from our fixed repository; do not open a URL supplied in release content.
        val page = "${UpdateSource.RELEASES_URL}/tag/${URLEncoder.encode(tag, "UTF-8").replace("+", "%20")}"
        val notes = if (json.isNull("body")) "" else json.optString("body").take(12_000)
        return UpdateResult.Available(AppRelease(tag, notes, page, hasApk))
    }
}
