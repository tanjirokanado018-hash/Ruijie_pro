package com.ruijiepro.network.models

data class GatewayInfo(
    val ipAddress: String,
    val macAddress: String
)

data class SessionResponse(
    val sessionId: String
)

data class VoucherRequest(
    val accessCode: String,
    val sessionId: String,
    val apiVersion: Int = 1,
    val captcha: String? = null
)

data class VoucherResponse(
    val logonUrl: String? = null,
    val message: String? = null,
    val success: Boolean? = null
)

data class CaptchaVerifyRequest(
    val sessionId: String,
    val authCode: String
)

data class CaptchaVerifyResponse(
    val success: Boolean? = null,
    val message: String? = null
)

sealed class LoginStep {
    object Idle : LoginStep()
    object DetectingGateway : LoginStep()
    object FetchingSession : LoginStep()
    object CheckingCaptcha : LoginStep()
    data class CaptchaRequired(val imageBytes: ByteArray) : LoginStep()
    object SubmittingVoucher : LoginStep()
    object ActivatingInternet : LoginStep()
    object Success : LoginStep()
    data class Error(val message: String) : LoginStep()
}
