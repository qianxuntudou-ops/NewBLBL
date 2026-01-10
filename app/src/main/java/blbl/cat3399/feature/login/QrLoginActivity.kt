package blbl.cat3399.feature.login

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityQrLoginBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class QrLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrLoginBinding
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        binding.btnRefresh.setOnClickListener { startFlow() }
        binding.btnClear.setOnClickListener {
            BiliClient.cookies.clearAll()
            binding.tvStatus.text = "已清除 Cookie（SESSDATA 等）"
        }

        startFlow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            binding.btnRefresh.post { binding.btnRefresh.requestFocus() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (currentFocus == null) {
            binding.btnRefresh.post { binding.btnRefresh.requestFocus() }
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private fun startFlow() {
        pollJob?.cancel()
        binding.tvStatus.text = "正在申请二维码..."
        lifecycleScope.launch {
            try {
                val gen = BiliClient.getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
                val data = gen.optJSONObject("data") ?: JSONObject()
                val url = data.optString("url", "")
                val key = data.optString("qrcode_key", "")
                AppLog.i("QrLogin", "generate ok key=${key.take(6)}")
                val qrSizePx = 500
                val bmp = withContext(Dispatchers.Default) { makeQr(url, qrSizePx) }
                binding.ivQr.setImageBitmap(bmp)
                binding.tvStatus.text = "请使用哔哩哔哩 App 扫码并确认登录"
                pollJob = poll(key)
            } catch (t: Throwable) {
                AppLog.e("QrLogin", "generate failed", t)
                binding.tvStatus.text = "申请二维码失败：${t.message}"
            }
        }
    }

    private fun poll(key: String): Job {
        return lifecycleScope.launch {
            val pollUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$key"
            while (isActive) {
                try {
                    val json = BiliClient.getJson(pollUrl)
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val code = data.optInt("code", -1)
                    val msg = data.optString("message", "")
                    when (code) {
                        86101 -> binding.tvStatus.text = "等待扫码..."
                        86090 -> binding.tvStatus.text = "已扫码，请在手机端确认"
                        86038 -> {
                            binding.tvStatus.text = "二维码已失效，请刷新"
                            return@launch
                        }

                        0 -> {
                            val refreshToken = data.optString("refresh_token", "").trim()
                            if (refreshToken.isNotBlank()) {
                                BiliClient.prefs.webRefreshToken = refreshToken
                            }
                            val crossDomainUrl = data.optString("url", "").trim()
                            if (crossDomainUrl.isNotBlank()) {
                                runCatching { BiliClient.requestString(crossDomainUrl) }
                            }
                            binding.tvStatus.text = "登录成功，Cookie 已写入（返回上一页）"
                            AppLog.i("QrLogin", "login success sess=${BiliClient.cookies.hasSessData()}")
                            delay(800)
                            finish()
                            return@launch
                        }

                        else -> binding.tvStatus.text = "状态：$code $msg"
                    }
                } catch (t: Throwable) {
                    AppLog.w("QrLogin", "poll failed", t)
                    binding.tvStatus.text = "轮询失败：${t.message}"
                }
                delay(2000)
            }
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }
}
