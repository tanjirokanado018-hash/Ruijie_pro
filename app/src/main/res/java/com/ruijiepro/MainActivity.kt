package com.ruijiepro

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ruijiepro.databinding.ActivityMainBinding
import com.ruijiepro.network.models.LoginStep
import com.ruijiepro.utils.PreferencesManager
import com.ruijiepro.viewmodel.MainViewModel
import com.ruijiepro.viewmodel.MainViewModelFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var captchaDialog: Dialog? = null

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(PreferencesManager(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        // Pre-fill last voucher
        viewModel.getLastVoucher()?.let { binding.etVoucher.setText(it) }

        // Gateway info
        updateGatewayDisplay()

        // Connect
        binding.btnConnect.setOnClickListener {
            val voucher = binding.etVoucher.text.toString().trim()
            if (voucher.isBlank()) {
                binding.etVoucher.error = "Voucher Code လိုအပ်သည်"
                shakeView(binding.etVoucher)
                return@setOnClickListener
            }
            clearLog()
            viewModel.startLogin(voucher)
        }

        // Paste
        binding.btnPasteVoucher.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString().trim()
                binding.etVoucher.setText(text)
                binding.etVoucher.setSelection(text.length)
                showToast("📋 Pasted")
            }
        }

        // Clear gateway
        binding.btnClearGateway.setOnClickListener {
            viewModel.clearGateway()
            binding.tvGatewayInfo.text = "No saved gateway"
            showToast("🗑 Gateway cleared")
        }

        // Clear log
        binding.btnClearLog.setOnClickListener {
            clearLog()
        }
    }

    private fun observeViewModel() {
        viewModel.step.observe(this) { step ->
            when (step) {
                is LoginStep.Idle -> {
                    setLoading(false)
                    setStatus("⏳", "Voucher Code ထည့်ပြီး Connect နှိပ်ပါ", R.color.text_primary)
                    updateGatewayDisplay()
                }
                is LoginStep.DetectingGateway -> {
                    setLoading(true)
                    setStatus("🔍", "Gateway ရှာဖွေနေသည်...", R.color.color_info)
                }
                is LoginStep.FetchingSession -> {
                    setLoading(true)
                    setStatus("🔗", "Session ID ရယူနေသည်...", R.color.color_info)
                }
                is LoginStep.CheckingCaptcha -> {
                    setLoading(true)
                    setStatus("🔐", "CAPTCHA စစ်ဆေးနေသည်...", R.color.color_info)
                }
                is LoginStep.CaptchaRequired -> {
                    setLoading(false)
                    setStatus("📷", "CAPTCHA လိုအပ်သည်...", R.color.color_warning)
                    showCaptchaDialog(step.imageBytes)
                }
                is LoginStep.SubmittingVoucher -> {
                    setLoading(true)
                    setStatus("📤", "Voucher ပေးပို့နေသည်...", R.color.color_info)
                }
                is LoginStep.ActivatingInternet -> {
                    setLoading(true)
                    setStatus("🌐", "Internet အသက်သွင်းနေသည်...", R.color.cyan_primary)
                }
                is LoginStep.Success -> {
                    setLoading(false)
                    setStatus("✅", "Internet ချိတ်ဆက်မှု အောင်မြင်ပါသည်!", R.color.color_success)
                    pulseButton()
                    updateGatewayDisplay()
                }
                is LoginStep.Error -> {
                    setLoading(false)
                    setStatus("❌", step.message, R.color.color_error)
                    shakeView(binding.tvStatus)
                    showToast(step.message)
                }
            }
        }

        viewModel.log.observe(this) { logText ->
            binding.tvLog.text = logText
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // ─── CAPTCHA Dialog ──────────────────────────────────────────────────────

    private fun showCaptchaDialog(imageBytes: ByteArray) {
        captchaDialog?.dismiss()

        val dialog = Dialog(this, R.style.CaptchaDialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_captcha)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(false)

        val imgCaptcha = dialog.findViewById<ImageView>(R.id.imgCaptcha)
        val etCode = dialog.findViewById<EditText>(R.id.etCaptchaCode)
        val btnRefresh = dialog.findViewById<Button>(R.id.btnRefreshCaptcha)
        val btnVerify = dialog.findViewById<Button>(R.id.btnCaptchaVerify)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCaptchaCancel)

        // Show image
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        imgCaptcha.setImageBitmap(bitmap)

        btnVerify.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.isBlank()) {
                etCode.error = "Code ထည့်ပါ"
                shakeView(etCode)
                return@setOnClickListener
            }
            dialog.dismiss()
            viewModel.submitCaptchaAndContinue(code)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            viewModel.resetState()
            setStatus("❌", "CAPTCHA ဖျက်သိမ်းလိုက်သည်", R.color.color_error)
        }

        btnRefresh.setOnClickListener {
            dialog.dismiss()
            setStatus("🔄", "CAPTCHA ပြန်ရယူနေသည်...", R.color.color_info)
            viewModel.refreshCaptcha()
        }

        captchaDialog = dialog
        dialog.show()
    }

    // ─── UI Helpers ───────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !loading
        binding.btnConnectBg.alpha = if (loading) 0.5f else 1.0f
        binding.btnConnect.alpha = if (loading) 0.7f else 1.0f
    }

    private fun setStatus(icon: String, message: String, colorRes: Int) {
        binding.tvStatusIcon.text = icon
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun clearLog() {
        binding.tvLog.text = "$ ready...\n"
        viewModel.log.value?.let { }
    }

    private fun updateGatewayDisplay() {
        val (ip, mac) = viewModel.getSavedGatewayInfo()
        binding.tvGatewayInfo.text = if (ip != null) "$ip · $mac" else "No saved gateway"
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // Shake animation for errors
    private fun shakeView(view: View) {
        val animator = ObjectAnimator.ofFloat(view, "translationX",
            0f, -16f, 16f, -12f, 12f, -8f, 8f, 0f)
        animator.duration = 400
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.start()
    }

    // Pulse animation for success
    private fun pulseButton() {
        val animator = ValueAnimator.ofFloat(1f, 1.04f, 1f)
        animator.duration = 400
        animator.repeatCount = 2
        animator.addUpdateListener { anim ->
            val scale = anim.animatedValue as Float
            binding.btnConnect.scaleX = scale
            binding.btnConnect.scaleY = scale
            binding.btnConnectBg.scaleX = scale
            binding.btnConnectBg.scaleY = scale
        }
        animator.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        captchaDialog?.dismiss()
    }
}
