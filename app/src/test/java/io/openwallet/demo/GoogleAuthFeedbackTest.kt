package io.openwallet.demo

import android.app.Activity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import io.openwallet.demo.ui.*
import org.junit.Assert.*
import org.junit.Test

class GoogleAuthFeedbackTest {
    @Test fun canceledActivityStillReadsGoogleConfigurationFailure() {
        val outcome = readGoogleAuthResult(Activity.RESULT_CANCELED, true) {
            throw ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR))
        } as GoogleAuthOutcome.Failure
        assertTrue(outcome.feedback.message.contains("OAuth 配置错误"))
        assertTrue(outcome.feedback.message.contains("Google 10"))
        assertFalse(outcome.feedback.message.contains("已取消"))
    }

    @Test fun closedActivityWithoutDataDoesNotClaimUserCancellation() {
        val outcome = readGoogleAuthResult(Activity.RESULT_CANCELED, false) {
            fail("There is no intent to parse")
        } as GoogleAuthOutcome.Failure
        assertTrue(outcome.feedback.message.contains("未返回具体原因"))
        assertTrue(outcome.feedback.message.contains("返回码 0"))
        assertFalse(outcome.feedback.message.contains("已取消"))
    }

    @Test fun onlyExplicitGoogleCanceledStatusReportsCancellation() {
        val outcome = readGoogleAuthResult(Activity.RESULT_CANCELED, true) {
            throw ApiException(Status(CommonStatusCodes.CANCELED))
        } as GoogleAuthOutcome.Failure
        assertTrue(outcome.feedback.message.contains("授权已取消"))
        assertEquals("apiStatus=16", outcome.feedback.diagnostic)
    }

    @Test fun connectionRequiresSuccessfulActivityAndParsedResult() {
        assertEquals(GoogleAuthOutcome.Success("granted"), readGoogleAuthResult(Activity.RESULT_OK, true) { "granted" })
        assertTrue(readGoogleAuthResult(Activity.RESULT_OK, false) { fail("Missing data") } is GoogleAuthOutcome.Failure)
        assertTrue(readGoogleAuthResult(Activity.RESULT_CANCELED, true) { "granted" } is GoogleAuthOutcome.Failure)
    }

    @Test fun unrecognizedExceptionDoesNotExposeItsSensitiveMessage() {
        val outcome = readGoogleAuthResult(Activity.RESULT_OK, true) {
            throw IllegalStateException("private-account access-token")
        } as GoogleAuthOutcome.Failure
        assertTrue(outcome.feedback.message.contains("IllegalStateException"))
        assertFalse(outcome.feedback.toString().contains("private-account"))
        assertFalse(outcome.feedback.toString().contains("access-token"))
    }

    @Test fun unavailableServicesAndNetworkHaveDifferentRecoverySteps() {
        assertTrue(GoogleAuthErrors.services(ConnectionResult.SERVICE_INVALID).message.contains("带 Google Play"))
        assertTrue(GoogleAuthErrors.services(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED).message.contains("更新"))
        assertTrue(GoogleAuthErrors.api(CommonStatusCodes.NETWORK_ERROR).message.contains("检查网络"))
        assertTrue(GoogleAuthErrors.api(9876).message.contains("Google 9876"))
    }
}
