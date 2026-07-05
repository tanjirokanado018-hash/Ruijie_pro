package com.ruijiepro.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ruijiepro.network.RuijieClient
import com.ruijiepro.network.models.LoginStep
import com.ruijiepro.utils.PreferencesManager
import kotlinx.coroutines.launch

class MainViewModel(private val prefs: PreferencesManager) : ViewModel() {

    val client = RuijieClient(prefs)

    private val _step = MutableLiveData<LoginStep>(LoginStep.Idle)
    val step: LiveData<LoginStep> = _step

    private val _log = MutableLiveData<String>("")
    val log: LiveData<String> = _log

    private val _captchaRequired = MutableLiveData<Boolean>(false)
    val captchaRequired: LiveData<Boolean> = _captchaRequired

    // Holds image bytes when CAPTCHA is required
    private var captchaImageBytes: ByteArray? = null
    private var pendingVoucher: String? = null

    init {
        client.onLog = { msg ->
            val current = _log.value ?: ""
            _log.postValue(current + msg + "\n")
        }
    }

    fun startLogin(voucher: String) {
        if (voucher.isBlank()) {
            _step.value = LoginStep.Error("Voucher code မထည့်ရသေးပါ")
            return
        }
        pendingVoucher = voucher
        prefs.lastVoucher = voucher
        _log.value = ""
        client.logBuilder.clear()

        viewModelScope.launch {
            // Step 1
            _step.postValue(LoginStep.DetectingGateway)
            val gwResult = client.detectGateway()
            if (gwResult.isFailure) {
                _step.postValue(LoginStep.Error(gwResult.exceptionOrNull()?.message ?: "Gateway detection failed"))
                return@launch
            }

            // Step 2
            _step.postValue(LoginStep.FetchingSession)
            val sidResult = client.fetchSessionId()
            if (sidResult.isFailure) {
                _step.postValue(LoginStep.Error(sidResult.exceptionOrNull()?.message ?: "Session fetch failed"))
                return@launch
            }

            // Step 3
            _step.postValue(LoginStep.CheckingCaptcha)
            val captchaResult = client.fetchCaptchaImage()
            if (captchaResult.isFailure) {
                _step.postValue(LoginStep.Error(captchaResult.exceptionOrNull()?.message ?: "CAPTCHA check failed"))
                return@launch
            }

            val imageBytes = captchaResult.getOrNull()
            if (imageBytes != null) {
                captchaImageBytes = imageBytes
                _step.postValue(LoginStep.CaptchaRequired(imageBytes))
                // Wait for user to submit CAPTCHA via submitCaptchaAndContinue()
            } else {
                // No CAPTCHA needed — continue directly
                continueAfterCaptcha(null)
            }
        }
    }

    fun submitCaptchaAndContinue(captchaCode: String) {
        viewModelScope.launch {
            val verifyResult = client.verifyCaptcha(captchaCode)
            if (verifyResult.isFailure) {
                _step.postValue(LoginStep.Error(verifyResult.exceptionOrNull()?.message ?: "CAPTCHA verify error"))
                return@launch
            }
            val verified = verifyResult.getOrNull() ?: false
            if (!verified) {
                _step.postValue(LoginStep.Error("CAPTCHA code မှားနေပါသည်။ ထပ်ကြိုးစားပါ။"))
                // Refresh captcha
                val captchaResult = client.fetchCaptchaImage()
                val bytes = captchaResult.getOrNull()
                if (bytes != null) {
                    _step.postValue(LoginStep.CaptchaRequired(bytes))
                }
                return@launch
            }
            continueAfterCaptcha(captchaCode)
        }
    }

    fun skipCaptcha() {
        viewModelScope.launch {
            continueAfterCaptcha(null)
        }
    }

    private suspend fun continueAfterCaptcha(captchaCode: String?) {
        val voucher = pendingVoucher ?: return

        // Step 4
        _step.postValue(LoginStep.SubmittingVoucher)
        val voucherResult = client.submitVoucher(voucher, captchaCode)
        if (voucherResult.isFailure) {
            _step.postValue(LoginStep.Error(voucherResult.exceptionOrNull()?.message ?: "Voucher submit error"))
            return
        }
        val accepted = voucherResult.getOrNull() ?: false
        if (!accepted) {
            _step.postValue(LoginStep.Error("Voucher ကို လက်မခံပါ။ Voucher Code စစ်ဆေးပြီး ထပ်ကြိုးစားပါ။"))
            return
        }

        // Step 5
        _step.postValue(LoginStep.ActivatingInternet)
        val activateResult = client.activateInternet()
        if (activateResult.isFailure) {
            _step.postValue(LoginStep.Error(activateResult.exceptionOrNull()?.message ?: "Activation error"))
            return
        }
        val activated = activateResult.getOrNull() ?: false
        _step.postValue(
            if (activated) LoginStep.Success
            else LoginStep.Error("Internet Activation မအောင်မြင်ပါ")
        )
    }

    fun refreshCaptcha() {
        viewModelScope.launch {
            val captchaResult = client.fetchCaptchaImage()
            val bytes = captchaResult.getOrNull()
            if (bytes != null) {
                _step.postValue(LoginStep.CaptchaRequired(bytes))
            }
        }
    }

    fun resetState() {
        _step.value = LoginStep.Idle
        _log.value = ""
    }

    fun getSavedGatewayInfo(): Pair<String?, String?> =
        Pair(prefs.gatewayIp, prefs.gatewayMac)

    fun getLastVoucher(): String? = prefs.lastVoucher

    fun clearGateway() {
        prefs.clearGateway()
        client.gatewayIp = null
        client.gatewayMac = null
    }
}
