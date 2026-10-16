package io.openwallet.demo.ui

import android.app.Activity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes

internal data class GoogleAuthFeedback(val message: String, val diagnostic: String)

internal object GoogleAuthErrors {
    fun services(code: Int): GoogleAuthFeedback = GoogleAuthFeedback(when (code) {
        ConnectionResult.SERVICE_MISSING -> "设备未安装 Google Play 服务，请使用带 Google Play 的设备或模拟器（服务码 $code）"
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "Google Play 服务版本过旧，请更新后重试（服务码 $code）"
        ConnectionResult.SERVICE_DISABLED -> "Google Play 服务已停用，请在系统设置中启用（服务码 $code）"
        ConnectionResult.SERVICE_INVALID -> "Google Play 服务缺失或不受支持，请使用带 Google Play 的设备或模拟器（服务码 $code）"
        ConnectionResult.SERVICE_UPDATING -> "Google Play 服务正在更新，请稍后重试（服务码 $code）"
        else -> "Google Play 服务暂不可用，请稍后重试（服务码 $code）"
    }, "serviceStatus=$code")

    fun api(code: Int): GoogleAuthFeedback = GoogleAuthFeedback(when (code) {
        CommonStatusCodes.CANCELED -> "Google 授权已取消，可重新点击连接（Google $code）"
        CommonStatusCodes.NETWORK_ERROR -> "无法连接 Google，请检查网络后重试（Google $code）"
        CommonStatusCodes.TIMEOUT -> "Google 授权超时，请重试（Google $code）"
        CommonStatusCodes.DEVELOPER_ERROR -> "Google OAuth 配置错误，请核对 Android 客户端的包名和签名 SHA-1（Google $code）"
        CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.INVALID_ACCOUNT -> "Google 账户不可用，请在设备上登录目标账户后重试（Google $code）"
        CommonStatusCodes.API_NOT_CONNECTED -> "未能连接 Google 授权服务，请重试（Google $code）"
        CommonStatusCodes.INTERNAL_ERROR -> "Google 授权服务内部错误，请稍后重试（Google $code）"
        else -> "Google 授权失败，请重试；若持续失败，请反馈错误码（Google $code）"
    }, "apiStatus=$code")

    fun noResult(resultCode: Int) = GoogleAuthFeedback(
        if (resultCode == Activity.RESULT_CANCELED)
            "授权页面已关闭，未返回具体原因，请重新连接（返回码 $resultCode）"
        else "未取得完整授权结果，请重新连接（返回码 $resultCode）",
        "activityResult=$resultCode"
    )

    fun unexpected(stage: String, exception: Exception) = GoogleAuthFeedback(
        "$stage，请重试；若持续失败，请反馈错误类型（${exception.javaClass.simpleName}）",
        "exception=${exception.javaClass.simpleName}"
    )
}

internal sealed interface GoogleAuthOutcome<out T> {
    data class Success<T>(val result: T) : GoogleAuthOutcome<T>
    data class Failure(val feedback: GoogleAuthFeedback) : GoogleAuthOutcome<Nothing>
}

// Google can return an ApiException in a non-OK activity result. Read it before
// classifying the outcome; RESULT_CANCELED alone does not prove user cancellation.
internal fun <T> readGoogleAuthResult(resultCode: Int, hasData: Boolean, parse: () -> T): GoogleAuthOutcome<T> {
    if (!hasData) return GoogleAuthOutcome.Failure(GoogleAuthErrors.noResult(resultCode))
    return try {
        val parsed = parse()
        if (resultCode == Activity.RESULT_OK) GoogleAuthOutcome.Success(parsed)
        else GoogleAuthOutcome.Failure(GoogleAuthErrors.noResult(resultCode))
    } catch (e: ApiException) {
        GoogleAuthOutcome.Failure(GoogleAuthErrors.api(e.statusCode))
    } catch (e: Exception) {
        GoogleAuthOutcome.Failure(GoogleAuthErrors.unexpected("读取 Google 授权结果失败", e))
    }
}
