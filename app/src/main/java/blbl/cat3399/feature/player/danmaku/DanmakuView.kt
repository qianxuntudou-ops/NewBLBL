package blbl.cat3399.feature.player.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import kotlin.math.abs

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val engine = DanmakuEngine()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(18f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        textSize = fill.textSize
        typeface = Typeface.DEFAULT_BOLD
    }

    private var positionProvider: (() -> Long)? = null
    private var configProvider: (() -> DanmakuConfig)? = null
    private var lastPositionMs: Long = 0L
    private var lastDrawUptimeMs: Long = 0L

    fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun setConfigProvider(provider: () -> DanmakuConfig) {
        configProvider = provider
    }

    fun setDanmakus(list: List<Danmaku>) {
        AppLog.i("DanmakuView", "setDanmakus size=${list.size}")
        engine.setDanmakus(list)
        invalidate()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0) {
        if (list.isEmpty()) return
        engine.appendDanmakus(list)
        if (maxItems > 0) engine.trimToMax(maxItems)
        invalidate()
    }

    fun notifySeek(positionMs: Long) {
        engine.seekTo(positionMs)
        lastPositionMs = positionMs
        lastDrawUptimeMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val provider = positionProvider ?: return
        val config = configProvider?.invoke() ?: defaultConfig()
        if (!config.enabled) return

        val positionMs = provider()
        val now = SystemClock.uptimeMillis()
        if (lastDrawUptimeMs == 0L) lastDrawUptimeMs = now

        // Detect big jump.
        if (abs(positionMs - lastPositionMs) > 3_000) {
            engine.seekTo(positionMs)
        }
        lastPositionMs = positionMs
        lastDrawUptimeMs = now

        fill.alpha = (config.opacity * 255).toInt().coerceIn(0, 255)
        stroke.alpha = (config.opacity * 220).toInt().coerceIn(0, 255)
        fill.textSize = sp(config.textSizeSp)
        stroke.textSize = fill.textSize

        val active = engine.update(
            width = width,
            height = height,
            positionMs = positionMs,
            paint = fill,
            speedLevel = config.speedLevel,
            area = config.area,
            topInsetPx = safeTopInsetPx(),
            bottomInsetPx = safeBottomInsetPx(),
        )

        for (a in active) {
            val color = (0xFF000000.toInt() or a.danmaku.color.toInt())
            fill.color = color
            stroke.color = 0xCC000000.toInt()
            canvas.drawText(a.danmaku.text, a.x, a.y, stroke)
            canvas.drawText(a.danmaku.text, a.x, a.y, fill)
        }

        postInvalidateOnAnimation()
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun safeTopInsetPx(): Int {
        // Avoid status bar + our top buttons overlapping danmaku.
        val statusBar = runCatching {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        return statusBar + dp(8f)
    }

    private fun safeBottomInsetPx(): Int {
        // Avoid player controller area; conservative default.
        return dp(52f)
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun defaultConfig(): DanmakuConfig {
        val prefs = BiliClient.prefs
        return DanmakuConfig(
            enabled = prefs.danmakuEnabled,
            opacity = prefs.danmakuOpacity,
            textSizeSp = prefs.danmakuTextSizeSp,
            speedLevel = prefs.danmakuSpeed,
            area = prefs.danmakuArea,
        )
    }
}
