package com.faaz.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class MediaInfo(val title: String, val artist: String, val art: Bitmap?, val playing: Boolean)

data class Alert(
    val kind: Kind,
    val title: String,
    val text: String,
    val icon: Bitmap? = null,
    val intent: PendingIntent? = null,
    val pkg: String? = null,
    val key: String? = null,
    val autoCancel: Boolean = false,
    val durationMs: Long = 4000L
) {
    enum class Kind { NOTIFICATION, CHARGING, TIMER_DONE }
}

enum class Action { OPEN_ALERT, TORCH, TIMER_1, TIMER_5, TIMER_STOP, EYES, PLAY_PAUSE, NEXT, PREV, SETTINGS }

@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun resizeWindow(w: Int, h: Int)
        fun onAction(action: Action, alert: Alert?)
        fun onTimerDone()
    }

    private enum class Mode { IDLE, LIVE, ALERT, EXPANDED }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---------- data the service pushes in ----------
    var baseW = dp(96f)
    var baseH = dp(30f)
    var battery = 0
    var charging = false
    var torchOn = false
    var timerEnd = 0L
    var timerTotal = 0L
    var media: MediaInfo? = null
    var eyes = false
    var paused = false
        set(value) {
            field = value
            if (!value) startTicking()
        }

    // ---------- state ----------
    private var mode = Mode.IDLE
    private var alert: Alert? = null
    private var alertStart = 0L
    private var page = 0 // 0 = controls, 1 = music
    private var curW = 0f
    private var curH = 0f
    private var contentAlpha = 1f
    private var anim: ValueAnimator? = null
    private val buttons = ArrayList<Pair<RectF, Action>>()

    // eyes
    private var lookX = 0f
    private var lookY = 0f
    private var tLookX = 0f
    private var tLookY = 0f
    private var nextLook = 0L
    private var blinkStart = -1000L
    private var nextBlink = 0L

    // ---------- paints ----------
    private val green = 0xFF7CF29C.toInt()
    private val orange = 0xFFFFA94D.toInt()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(15f); typeface = Typeface.DEFAULT_BOLD
    }
    private val subP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8A8B0.toInt(); textSize = dp(13f)
    }
    private val bigP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(40f)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val emojiP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(22f); textAlign = Paint.Align.CENTER
    }
    private val capP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8A8B0.toInt(); textSize = dp(11f); textAlign = Paint.Align.CENTER
    }
    private val liveP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = orange; textSize = dp(14f); typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()
    private val r2 = RectF()
    private val path = Path()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

    private var left0 = 0f
    private var right0 = 0f

    // =====================================================================
    // sizes + animation
    // =====================================================================

    private fun screenW() = resources.displayMetrics.widthPixels.toFloat()
    private fun timerRunning() = timerEnd > System.currentTimeMillis()
    private fun hasLive() = timerRunning() || media?.playing == true

    private fun target(): Pair<Float, Float> {
        val maxW = screenW() - dp(20f)
        return when (mode) {
            Mode.IDLE -> (if (eyes) max(baseW, baseH * 3.8f) else baseW) to baseH
            Mode.LIVE -> min(baseW + dp(130f), maxW) to baseH
            Mode.ALERT -> min(dp(360f), maxW) to max(dp(68f), baseH)
            Mode.EXPANDED -> min(dp(400f), maxW) to dp(200f)
        }
    }

    private fun go(m: Mode) {
        mode = m
        val (tw, th) = target()
        val sw = if (curW <= 0f) tw else curW
        val sh = if (curH <= 0f) th else curH
        anim?.cancel()
        val growing = tw * th > sw * sh + 1f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (growing) 480L else 320L
            interpolator = if (growing) OvershootInterpolator(1.15f) else DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                curW = sw + (tw - sw) * f
                curH = max(dp(10f), sh + (th - sh) * f)
                contentAlpha = ((it.animatedFraction - 0.5f) / 0.5f).coerceIn(0f, 1f)
                host.resizeWindow(ceil(curW).toInt() + 2, ceil(curH).toInt() + 2)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    contentAlpha = 1f
                    invalidate()
                }
            })
            start()
        }
        startTicking()
    }

    /** Call when data changes (music, timer, battery…). */
    fun refresh() {
        if (mode == Mode.ALERT || mode == Mode.EXPANDED) {
            invalidate(); startTicking(); return
        }
        go(if (hasLive()) Mode.LIVE else Mode.IDLE)
    }

    /** Call when size settings change. */
    fun relayout() = go(mode)

    fun showAlert(a: Alert) {
        if (mode == Mode.EXPANDED) return
        alert = a
        alertStart = SystemClock.uptimeMillis()
        removeCallbacks(endAlert)
        postDelayed(endAlert, a.durationMs)
        go(Mode.ALERT)
        haptic()
    }

    private val endAlert = Runnable {
        alert = null
        mode = Mode.IDLE
        refresh()
    }

    private fun dismissAlert() {
        removeCallbacks(endAlert)
        endAlert.run()
    }

    private fun expand() {
        removeCallbacks(endAlert)
        alert = null
        page = if (media != null) 1 else 0
        go(Mode.EXPANDED)
        bumpAutoCollapse()
        haptic()
    }

    fun collapse() {
        if (mode != Mode.EXPANDED) return
        removeCallbacks(autoCollapse)
        mode = Mode.IDLE
        refresh()
    }

    private val autoCollapse = Runnable { collapse() }
    private fun bumpAutoCollapse() {
        removeCallbacks(autoCollapse)
        postDelayed(autoCollapse, 8000L)
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // =====================================================================
    // frame ticker (eyes, equalizer, clock, timer)
    // =====================================================================

    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (needsFrames()) {
                onTick()
                invalidate()
                postDelayed(this, 33L)
            } else {
                ticking = false
            }
        }
    }

    private fun needsFrames(): Boolean {
        if (paused || !isAttachedToWindow || visibility != VISIBLE) return false
        return (eyes && mode == Mode.IDLE) || mode == Mode.EXPANDED || mode == Mode.ALERT ||
                hasLive() || timerEnd != 0L
    }

    private fun startTicking() {
        if (!ticking && needsFrames()) {
            ticking = true
            post(ticker)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicking()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        startTicking()
    }

    private fun onTick() {
        if (timerEnd != 0L && System.currentTimeMillis() >= timerEnd) {
            timerEnd = 0L
            timerTotal = 0L
            host.onTimerDone()
            if (mode == Mode.LIVE) refresh()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now > nextLook) {
            tLookX = Random.nextFloat() * 2f - 1f
            tLookY = Random.nextFloat() * 1.2f - 0.6f
            nextLook = now + 900L + Random.nextLong(1600L)
        }
        lookX += (tLookX - lookX) * 0.18f
        lookY += (tLookY - lookY) * 0.18f
        if (now > nextBlink) {
            blinkStart = now
            nextBlink = now + 2500L + Random.nextLong(3500L)
        }
        // music stopped while showing live pill
        if (mode == Mode.LIVE && !hasLive()) refresh()
    }

    // =====================================================================
    // drawing
    // =====================================================================

    override fun onDraw(c: Canvas) {
        if (curW <= 0f) return
        left0 = (width - curW) / 2f
        right0 = left0 + curW
        rect.set(left0, 0f, right0, curH)
        val radius = min(curH / 2f, dp(36f))
        c.drawRoundRect(rect, radius, radius, bgPaint)
        if (mode == Mode.EXPANDED) buttons.clear()
        if (contentAlpha <= 0.02f) return

        c.saveLayerAlpha(rect, (contentAlpha * 255).toInt())
        c.clipRect(rect)
        when (mode) {
            Mode.IDLE -> if (eyes) drawEyes(c)
            Mode.LIVE -> drawLive(c)
            Mode.ALERT -> drawAlert(c)
            Mode.EXPANDED -> if (page == 1 && media != null) drawMedia(c) else drawControls(c)
        }
        c.restore()
    }

    private fun drawEyes(c: Canvas) {
        val cy = curH / 2f
        val r = curH * 0.3f
        val off = curW * 0.3f
        val t = SystemClock.uptimeMillis() - blinkStart
        val open = if (t in 0L..180L) abs(t - 90L) / 90f else 1f
        val oy = r * max(open, 0.12f)
        for (sx in floatArrayOf(-1f, 1f)) {
            val ex = width / 2f + sx * off
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            r2.set(ex - r, cy - oy, ex + r, cy + oy)
            c.drawOval(r2, paint)
            if (open > 0.45f) {
                paint.color = Color.BLACK
                c.drawCircle(ex + lookX * r * 0.45f, cy + lookY * r * 0.45f, r * 0.5f, paint)
            }
        }
    }

    private fun drawLive(c: Canvas) {
        val pad = curH * 0.16f
        val s = curH - pad * 2
        val l = left0 + pad + dp(6f)
        r2.set(l, pad, l + s, pad + s)
        val m = media
        if (timerRunning()) {
            // ring that empties as time runs out
            val left = (timerEnd - System.currentTimeMillis()).toFloat()
            val frac = if (timerTotal > 0) (left / timerTotal).coerceIn(0f, 1f) else 1f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.color = 0x55FFA94D
            r2.inset(dp(2f), dp(2f))
            c.drawOval(r2, paint)
            paint.color = orange
            c.drawArc(r2, -90f, 360f * frac, false, paint)
            paint.style = Paint.Style.FILL
            c.drawText(fmt(timerEnd - System.currentTimeMillis()), right0 - pad - dp(8f), baseline(curH / 2f, liveP), liveP)
        } else if (m != null) {
            val art = m.art
            if (art != null) drawRoundBitmap(c, art, r2, s * 0.3f)
            else {
                paint.style = Paint.Style.FILL; paint.color = 0xFF2A2A2E.toInt()
                c.drawRoundRect(r2, s * 0.3f, s * 0.3f, paint)
            }
            drawBars(c, right0 - pad - dp(8f), curH / 2f, s * 0.8f, m.playing)
        }
    }

    private fun drawBars(c: Canvas, right: Float, cy: Float, h: Float, playing: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = green
        val bw = dp(3f)
        val gap = dp(2.5f)
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until 4) {
            val amp = if (playing) 0.25f + 0.75f * abs(sin(t * (5f + i * 1.7f) + i * 1.3f)) else 0.2f
            val bh = h * amp
            val x = right - (4 - i) * (bw + gap)
            r2.set(x, cy - bh / 2f, x + bw, cy + bh / 2f)
            c.drawRoundRect(r2, bw / 2f, bw / 2f, paint)
        }
    }

    private fun drawAlert(c: Canvas) {
        val a = alert ?: return
        val pad = dp(12f)
        val s = curH - pad * 2
        val l = left0 + pad + dp(4f)
        r2.set(l, pad, l + s, pad + s)
        val cx = r2.centerX()
        val cy = r2.centerY()
        paint.style = Paint.Style.FILL
        when (a.kind) {
            Alert.Kind.NOTIFICATION -> {
                val ic = a.icon
                if (ic != null) drawRoundBitmap(c, ic, r2, s / 2f)
                else { paint.color = 0xFF2A2A2E.toInt(); c.drawCircle(cx, cy, s / 2f, paint) }
            }
            Alert.Kind.CHARGING -> {
                paint.color = 0xFF1F3A27.toInt(); c.drawCircle(cx, cy, s / 2f, paint)
                drawBolt(c, cx, cy, s / 2f)
            }
            Alert.Kind.TIMER_DONE -> {
                paint.color = 0xFF3A2A1A.toInt(); c.drawCircle(cx, cy, s / 2f, paint)
                c.drawText("⏰", cx, baseline(cy, emojiP), emojiP)
            }
        }

        val tx = r2.right + dp(12f)
        val extra = if (a.kind == Alert.Kind.CHARGING) dp(56f) else 0f
        val maxW = right0 - pad - dp(10f) - tx - extra
        if (maxW <= 0f) return
        val title = TextUtils.ellipsize(a.title, titleP, maxW, TextUtils.TruncateAt.END).toString()
        if (a.text.isBlank()) {
            c.drawText(title, tx, baseline(curH / 2f, titleP), titleP)
        } else {
            val text = TextUtils.ellipsize(a.text.replace('\n', ' '), subP, maxW, TextUtils.TruncateAt.END).toString()
            c.drawText(title, tx, curH / 2f - dp(4f), titleP)
            c.drawText(text, tx, curH / 2f + dp(14f), subP)
        }

        if (a.kind == Alert.Kind.CHARGING) {
            val bw = dp(40f); val bh = dp(18f)
            val bx = right0 - pad - dp(8f) - bw
            val by = curH / 2f - bh / 2f
            val grow = ((SystemClock.uptimeMillis() - alertStart) / 900f).coerceIn(0f, 1f)
            drawBattery(c, bx, by, bw, bh, battery / 100f * grow, true)
        }
    }

    private fun drawBattery(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, isCharging: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = 0xFF8A8A92.toInt()
        r2.set(x, y, x + w, y + h)
        c.drawRoundRect(r2, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.FILL
        r2.set(x + w + dp(1.5f), y + h * 0.3f, x + w + dp(4f), y + h * 0.7f)
        c.drawRoundRect(r2, dp(1f), dp(1f), paint)
        paint.color = when {
            isCharging -> green
            frac <= 0.2f -> 0xFFFF6B6B.toInt()
            else -> Color.WHITE
        }
        val inset = dp(3f)
        r2.set(x + inset, y + inset, x + inset + (w - inset * 2) * frac.coerceIn(0f, 1f), y + h - inset)
        c.drawRoundRect(r2, dp(2f), dp(2f), paint)
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, u: Float) {
        path.reset()
        path.moveTo(cx + 0.15f * u, cy - 0.6f * u)
        path.lineTo(cx - 0.35f * u, cy + 0.08f * u)
        path.lineTo(cx, cy + 0.08f * u)
        path.lineTo(cx - 0.15f * u, cy + 0.6f * u)
        path.lineTo(cx + 0.35f * u, cy - 0.08f * u)
        path.lineTo(cx, cy - 0.08f * u)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = green
        c.drawPath(path, paint)
    }

    private fun drawControls(c: Canvas) {
        val p = dp(22f)
        val now = Date()
        c.drawText(timeFmt.format(now), left0 + p, dp(58f), bigP)
        var date = dateFmt.format(now)
        if (timerRunning()) date += "  •  ⏱ " + fmt(timerEnd - System.currentTimeMillis())
        c.drawText(date, left0 + p, dp(82f), subP)

        // battery (top right)
        titleP.textAlign = Paint.Align.RIGHT
        c.drawText("$battery%", right0 - p, dp(42f), titleP)
        titleP.textAlign = Paint.Align.LEFT
        drawBattery(c, right0 - p - dp(38f), dp(52f), dp(34f), dp(16f), battery / 100f, charging)
        if (charging) drawBolt(c, right0 - p - dp(46f), dp(60f), dp(8f))

        if (media != null) drawDots(c, dp(98f))

        val stop = timerRunning()
        val items = listOf(
            Triple("🔦", "Torch", Action.TORCH) to torchOn,
            Triple("⏱", "+1 min", Action.TIMER_1) to false,
            Triple("⏳", "+5 min", Action.TIMER_5) to false,
            (if (stop) Triple("✋", "Stop timer", Action.TIMER_STOP)
            else Triple("👀", if (eyes) "Eyes off" else "Eyes", Action.EYES)) to (eyes && !stop)
        )
        val r = dp(26f)
        val cy = curH - dp(64f)
        val avail = curW - p * 2
        items.forEachIndexed { i, item ->
            val b = item.first
            val active = item.second
            val cx = left0 + p + avail * (i + 0.5f) / items.size
            paint.style = Paint.Style.FILL
            paint.color = if (active) 0xFFFFD54F.toInt() else 0xFF26262B.toInt()
            c.drawCircle(cx, cy, r, paint)
            c.drawText(b.first, cx, baseline(cy, emojiP), emojiP)
            c.drawText(b.second, cx, cy + r + dp(16f), capP)
            buttons.add(RectF(cx - r - dp(8f), cy - r - dp(8f), cx + r + dp(8f), cy + r + dp(22f)) to b.third)
        }
    }

    private fun drawMedia(c: Canvas) {
        val m = media ?: return
        val p = dp(20f)
        val s = dp(84f)
        r2.set(left0 + p, dp(18f), left0 + p + s, dp(18f) + s)
        val art = m.art
        if (art != null) drawRoundBitmap(c, art, r2, dp(14f))
        else {
            paint.style = Paint.Style.FILL; paint.color = 0xFF26262B.toInt()
            c.drawRoundRect(r2, dp(14f), dp(14f), paint)
            emojiP.textSize = dp(34f)
            c.drawText("🎵", r2.centerX(), baseline(r2.centerY(), emojiP), emojiP)
            emojiP.textSize = dp(22f)
        }
        val tx = r2.right + dp(14f)
        val maxW = right0 - p - tx
        if (maxW > 0f) {
            c.drawText(TextUtils.ellipsize(m.title, titleP, maxW, TextUtils.TruncateAt.END).toString(), tx, dp(52f), titleP)
            c.drawText(TextUtils.ellipsize(m.artist, subP, maxW, TextUtils.TruncateAt.END).toString(), tx, dp(72f), subP)
            drawBars(c, tx + dp(28f), dp(90f), dp(14f), m.playing)
        }

        drawDots(c, dp(118f))

        val cy = curH - dp(44f)
        val cx = (left0 + right0) / 2f
        val gap = dp(86f)
        paint.style = Paint.Style.FILL
        // previous
        paint.color = Color.WHITE
        drawSkip(c, cx - gap, cy, dp(11f), false)
        buttons.add(RectF(cx - gap - dp(30f), cy - dp(30f), cx - gap + dp(30f), cy + dp(30f)) to Action.PREV)
        // play / pause
        paint.color = Color.WHITE
        c.drawCircle(cx, cy, dp(28f), paint)
        paint.color = Color.BLACK
        if (m.playing) {
            r2.set(cx - dp(8f), cy - dp(10f), cx - dp(3f), cy + dp(10f)); c.drawRoundRect(r2, dp(1.5f), dp(1.5f), paint)
            r2.set(cx + dp(3f), cy - dp(10f), cx + dp(8f), cy + dp(10f)); c.drawRoundRect(r2, dp(1.5f), dp(1.5f), paint)
        } else {
            triangle(c, cx + dp(2f), cy, dp(11f), true)
        }
        buttons.add(RectF(cx - dp(32f), cy - dp(32f), cx + dp(32f), cy + dp(32f)) to Action.PLAY_PAUSE)
        // next
        paint.color = Color.WHITE
        drawSkip(c, cx + gap, cy, dp(11f), true)
        buttons.add(RectF(cx + gap - dp(30f), cy - dp(30f), cx + gap + dp(30f), cy + dp(30f)) to Action.NEXT)
    }

    private fun drawSkip(c: Canvas, cx: Float, cy: Float, size: Float, forward: Boolean) {
        val dir = if (forward) 1f else -1f
        triangle(c, cx - dir * size * 0.3f, cy, size, forward)
        r2.set(cx + dir * size * 0.6f - dp(1.5f), cy - size, cx + dir * size * 0.6f + dp(1.5f), cy + size)
        c.drawRect(r2, paint)
    }

    private fun triangle(c: Canvas, cx: Float, cy: Float, size: Float, right: Boolean) {
        val d = if (right) 1f else -1f
        path.reset()
        path.moveTo(cx + d * size * 0.8f, cy)
        path.lineTo(cx - d * size * 0.6f, cy - size)
        path.lineTo(cx - d * size * 0.6f, cy + size)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawDots(c: Canvas, y: Float) {
        val cx = (left0 + right0) / 2f
        paint.style = Paint.Style.FILL
        for (i in 0..1) {
            paint.color = if ((if (page == 1) 1 else 0) == i) Color.WHITE else 0xFF55555C.toInt()
            c.drawCircle(cx + (i - 0.5f) * dp(12f), y, dp(3f), paint)
        }
    }

    private fun drawRoundBitmap(c: Canvas, b: Bitmap, dst: RectF, radius: Float) {
        c.save()
        path.reset()
        path.addRoundRect(dst, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.isFilterBitmap = true
        c.drawBitmap(b, null, dst, paint)
        c.restore()
    }

    private fun baseline(centerY: Float, p: Paint): Float {
        val fm = p.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun fmt(ms: Long): String {
        val s = (max(0L, ms) + 999L) / 1000L
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // =====================================================================
    // touch
    // =====================================================================

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode == Mode.EXPANDED) {
                tapExpanded(e.x, e.y); return true
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            when (mode) {
                Mode.EXPANDED -> {}
                Mode.ALERT -> {
                    val a = alert
                    if (a != null && a.kind == Alert.Kind.NOTIFICATION) host.onAction(Action.OPEN_ALERT, a)
                    dismissAlert()
                }
                else -> expand()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            when (mode) {
                Mode.EXPANDED -> tapExpanded(e.x, e.y)
                Mode.IDLE, Mode.LIVE -> { haptic(); host.onAction(Action.EYES, null) }
                else -> {}
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            haptic()
            host.onAction(Action.SETTINGS, null)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            when (mode) {
                Mode.EXPANDED -> {
                    if (abs(velocityX) > abs(velocityY)) {
                        if (media != null) { page = 1 - page; haptic(); bumpAutoCollapse(); invalidate() }
                    } else if (velocityY < 0) collapse()
                }
                Mode.ALERT -> if (velocityY < 0) dismissAlert() else if (velocityY > 0) expand()
                else -> if (velocityY > 0) expand()
            }
            return true
        }
    })

    private fun tapExpanded(x: Float, y: Float) {
        bumpAutoCollapse()
        val hit = buttons.firstOrNull { it.first.contains(x, y) }
        if (hit != null) {
            haptic()
            host.onAction(hit.second, null)
            invalidate()
        } else {
            collapse()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            collapse()
            return false
        }
        return gd.onTouchEvent(event)
    }
}
