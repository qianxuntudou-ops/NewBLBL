package blbl.cat3399.feature.player.danmaku

import android.graphics.Paint
import blbl.cat3399.core.model.Danmaku
import kotlin.math.max
import kotlin.math.min

class DanmakuEngine {
    data class Active(
        val danmaku: Danmaku,
        val lane: Int,
        val textWidth: Float,
        val pxPerMs: Float,
        val durationMs: Int,
    )

    data class DrawItem(
        val danmaku: Danmaku,
        val x: Float,
        val y: Float,
        val textWidth: Float,
    )

    private var danmakus: MutableList<Danmaku> = mutableListOf()
    private var index = 0
    private val active = ArrayList<Active>()
    private val pending = ArrayDeque<Pending>()

    fun setDanmakus(list: List<Danmaku>) {
        danmakus = list.sortedBy { it.timeMs }.toMutableList()
        index = 0
        active.clear()
        pending.clear()
    }

    fun appendDanmakus(list: List<Danmaku>) {
        if (list.isEmpty()) return
        if (danmakus.isEmpty()) {
            setDanmakus(list)
            return
        }
        val sorted = list.sortedBy { it.timeMs }
        val last = danmakus.lastOrNull()?.timeMs ?: Int.MIN_VALUE
        if (sorted.first().timeMs >= last) {
            danmakus.addAll(sorted)
            return
        }
        // Fallback: merge & reset (rare for live mode).
        danmakus.addAll(sorted)
        danmakus.sortBy { it.timeMs }
        index = 0
        active.clear()
        pending.clear()
    }

    fun trimToMax(maxItems: Int) {
        if (maxItems <= 0) return
        val drop = danmakus.size - maxItems
        if (drop <= 0) return
        danmakus = danmakus.drop(drop).toMutableList()
        index = (index - drop).coerceAtLeast(0)
        active.clear()
        pending.clear()
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.toInt()
        index = danmakus.binarySearchBy(pos) { it.timeMs }.let { idx ->
            if (idx >= 0) idx else max(0, -idx - 1)
        }
        active.clear()
        pending.clear()
    }

    fun update(
        width: Int,
        height: Int,
        positionMs: Long,
        paint: Paint,
        speedLevel: Int,
        area: Float,
        topInsetPx: Int,
        bottomInsetPx: Int,
    ): List<DrawItem> {
        val safeTop = topInsetPx.coerceIn(0, height)
        val safeBottom = bottomInsetPx.coerceIn(0, height - safeTop)
        val availableHeight = (height - safeTop - safeBottom).coerceAtLeast(0)
        val laneHeight = max(18f, paint.textSize * 1.2f)
        val baselineOffset = paint.textSize
        val usableHeight = (availableHeight * area).toInt().coerceAtLeast(0)
        val laneCount = max(1, (usableHeight / laneHeight).toInt())

        val baseDurationMs = (6_000f / speedMultiplier(speedLevel)).toInt().coerceIn(2_000, 20_000)
        val nowMs = positionMs.toInt()

        pruneExpired(width, nowMs)
        skipOld(nowMs, baseDurationMs)

        val marginPx = max(12f, paint.textSize * 0.6f)

        // Build per-lane "last spawned" snapshot at current time.
        val laneLast = arrayOfNulls<Active>(laneCount)
        val laneLastTail = FloatArray(laneCount) { Float.NEGATIVE_INFINITY }
        for (a in active) {
            if (a.lane !in 0 until laneCount) continue
            val cur = laneLast[a.lane]
            if (cur == null || a.danmaku.timeMs > cur.danmaku.timeMs) {
                laneLast[a.lane] = a
            }
        }
        for (lane in 0 until laneCount) {
            val a = laneLast[lane] ?: continue
            val x = scrollX(width, nowMs, a.danmaku.timeMs, a.pxPerMs)
            laneLastTail[lane] = x + a.textWidth
        }

        fun trySpawn(d: Danmaku): Boolean {
            if (d.text.isBlank()) return true
            val tw = paint.measureText(d.text)
            val pxNew = (width + tw) / baseDurationMs.toFloat()

            for (lane in 0 until laneCount) {
                val prev = laneLast[lane]
                if (prev == null) {
                    val a = Active(d, lane, tw, pxNew, baseDurationMs)
                    active.add(a)
                    laneLast[lane] = a
                    laneLastTail[lane] = width.toFloat() + tw
                    return true
                }
                val tailPrev = laneLastTail[lane]
                if (isLaneAvailable(width.toFloat(), nowMs, prev, tailPrev, pxNew, marginPx)) {
                    val a = Active(d, lane, tw, pxNew, baseDurationMs)
                    active.add(a)
                    laneLast[lane] = a
                    laneLastTail[lane] = width.toFloat() + tw
                    return true
                }
            }
            return false
        }

        // Retry pending danmakus first (delay a bit to avoid overlaps).
        if (pending.isNotEmpty()) {
            val keep = ArrayDeque<Pending>(pending.size)
            while (pending.isNotEmpty()) {
                val p = pending.removeFirst()
                if (p.nextTryMs > nowMs) {
                    keep.addLast(p)
                    continue
                }
                val ok = trySpawn(p.danmaku)
                if (!ok) {
                    val age = nowMs - p.firstTryMs
                    if (age <= MAX_DELAY_MS) {
                        p.nextTryMs = nowMs + DELAY_STEP_MS
                        keep.addLast(p)
                    }
                }
            }
            pending.addAll(keep)
        }

        // Spawn: only spawn items whose timestamp <= current position (no early spawn).
        while (index < danmakus.size && danmakus[index].timeMs <= nowMs) {
            val d = danmakus[index]
            index++
            if (trySpawn(d)) continue
            enqueuePending(d, nowMs)
        }

        // Build draw list.
        val out = ArrayList<DrawItem>(active.size)
        for (a in active) {
            val x = scrollX(width, nowMs, a.danmaku.timeMs, a.pxPerMs)
            val y = (safeTop + baselineOffset + laneHeight * a.lane).coerceAtMost((safeTop + usableHeight).toFloat())
            out.add(DrawItem(a.danmaku, x, y, a.textWidth))
        }

        return out
    }

    private fun pruneExpired(width: Int, nowMs: Int) {
        val it = active.iterator()
        while (it.hasNext()) {
            val a = it.next()
            val elapsed = nowMs - a.danmaku.timeMs
            if (elapsed < 0) {
                it.remove()
                continue
            }
            if (elapsed >= a.durationMs) {
                it.remove()
                continue
            }
            val x = scrollX(width, nowMs, a.danmaku.timeMs, a.pxPerMs)
            if (x + a.textWidth < 0f) it.remove()
        }
    }

    private fun scrollX(width: Int, nowMs: Int, danmakuTimeMs: Int, pxPerMs: Float): Float {
        val elapsed = (nowMs - danmakuTimeMs).coerceAtLeast(0)
        return width.toFloat() - elapsed * pxPerMs
    }

    private fun isLaneAvailable(
        width: Float,
        nowMs: Int,
        front: Active,
        tailPrev: Float,
        pxNew: Float,
        marginPx: Float,
    ): Boolean {
        val elapsedPrev = nowMs - front.danmaku.timeMs
        val prevRemaining = front.durationMs - elapsedPrev
        if (prevRemaining <= 0) return true

        // Condition A: the previous danmaku's tail must be fully inside the screen.
        if (tailPrev + marginPx > width) return false

        // Condition B: if the new danmaku is faster, it must not catch up before the previous one exits.
        val pxPrev = front.pxPerMs
        if (pxNew <= pxPrev) return true

        val gap0 = (width - tailPrev - marginPx).coerceAtLeast(0f)
        val maxSafe = (pxNew - pxPrev) * prevRemaining
        return gap0 >= maxSafe
    }

    private fun skipOld(nowMs: Int, baseDurationMs: Int) {
        val ignoreBefore = nowMs - baseDurationMs
        while (index < danmakus.size && danmakus[index].timeMs < ignoreBefore) {
            index++
        }
    }

    private fun enqueuePending(danmaku: Danmaku, nowMs: Int) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(Pending(danmaku, nowMs + DELAY_STEP_MS, nowMs))
    }

    private data class Pending(
        val danmaku: Danmaku,
        var nextTryMs: Int,
        val firstTryMs: Int,
    )

    private fun speedMultiplier(level: Int): Float = when (min(10, max(1, level))) {
        1 -> 0.6f
        2 -> 0.8f
        3 -> 0.9f
        4 -> 1.0f
        5 -> 1.2f
        6 -> 1.4f
        7 -> 1.6f
        8 -> 1.9f
        9 -> 2.2f
        else -> 2.6f
    }

    private companion object {
        private const val DELAY_STEP_MS = 220
        private const val MAX_DELAY_MS = 1_600
        private const val MAX_PENDING = 260
    }
}
