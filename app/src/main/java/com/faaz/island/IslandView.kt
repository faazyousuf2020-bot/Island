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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
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

enum class Action { OPEN_ALERT, TORCH, TIMER_1, TIMER_5, TIMER_STOP, EYES, PLAY_PAUSE, NEXT, PREV, SETTINGS, GAME }

@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun resizeWindow(w: Int, h: Int)
        fun onAction(action: Action, alert: Alert?)
        fun onTimerDone()
        fun onGameBest(best: Int)
    }

    private enum class Mode { IDLE, LIVE, ALERT, EXPANDED, GAME }

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
    var camDx = 0f          // camera center relative to window center (x)
    var camY = dp(15f)      // camera center from window top (y)
    var camR = dp(7f)       // camera hole radius
    var camInUse = false
        set(value) {
            if (field != value) camRingStart = SystemClock.uptimeMillis()
            field = value
            startTicking()
        }
    private var camRingStart = 0L

    // pet mode (fed by the motion sensor)
    var tiltX = 0f
    var tiltY = 0f
    var lastMove = SystemClock.uptimeMillis()
    var dizzyUntil = 0L

    // music glow
    var glowOn = true
    var glowColor = 0xFF7CF29C.toInt()
    var levelSource: (() -> Float)? = null
    private var beat = 0f

    private enum class Mood { NORMAL, DIZZY, SURPRISED, SLEEPING, HAPPY, TIRED }
    private var mood = Mood.NORMAL
    private var surprisedUntil = 0L
    private var wasSleeping = false
    private var night = false
    private var nightCheckedAt = 0L
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
    private val zP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
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
            Mode.EXPANDED, Mode.GAME -> min(dp(400f), maxW) to dp(200f)
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
        if (mode == Mode.ALERT || mode == Mode.EXPANDED || mode == Mode.GAME) {
            invalidate(); startTicking(); return
        }
        go(if (hasLive()) Mode.LIVE else Mode.IDLE)
    }

    /** Call when size settings change. */
    fun relayout() = go(mode)

    fun showAlert(a: Alert) {
        if (mode == Mode.EXPANDED || mode == Mode.GAME) return
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
        if (mode != Mode.EXPANDED && mode != Mode.GAME) return
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
                postDelayed(this, if (mode == Mode.GAME) 16L else 33L)
            } else {
                ticking = false
            }
        }
    }

    private fun needsFrames(): Boolean {
        if (paused || !isAttachedToWindow || visibility != VISIBLE) return false
        return (eyes && mode == Mode.IDLE) || mode == Mode.EXPANDED || mode == Mode.GAME || mode == Mode.ALERT || camInUse ||
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
        if (mode == Mode.GAME) stepGame(now)
        updateMood(now)
        if (now - lastMove < 1500L) {
            // phone is moving → look the way it's tilted
            tLookX = tiltX
            tLookY = tiltY * 0.7f
        } else if (now > nextLook) {
            tLookX = Random.nextFloat() * 2f - 1f
            tLookY = Random.nextFloat() * 1.2f - 0.6f
            nextLook = now + 900L + Random.nextLong(1600L)
        }
        if (mood == Mood.TIRED) tLookY = 0.7f
        val ease = if (mood == Mood.TIRED) 0.06f else 0.18f
        lookX += (tLookX - lookX) * ease
        lookY += (tLookY - lookY) * ease

        // music glow level: real beat if available, otherwise a soft 125bpm pulse
        val raw = levelSource?.invoke() ?: exp(-(now % 480L) / 110f)
        beat = if (raw > beat) raw else beat * 0.88f
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
        val m = media
        if (glowOn && m != null && m.playing &&
            (mode == Mode.LIVE || (mode == Mode.EXPANDED && page == 1))
        ) drawGlow(c, radius)
        if (camInUse && (mode == Mode.IDLE || mode == Mode.LIVE)) drawCamRing(c)
        when (mode) {
            Mode.IDLE -> if (eyes) drawEyes(c)
            Mode.LIVE -> drawLive(c)
            Mode.ALERT -> drawAlert(c)
            Mode.EXPANDED -> if (page == 1 && media != null) drawMedia(c) else drawControls(c)
            Mode.GAME -> drawGame(c)
        }
        c.restore()
    }

    /** Glowing rings that spin around the camera hole while an app uses the camera. */
    private fun drawCamRing(c: Canvas) {
        val cx = width / 2f + camDx
        val cy = camY
        val maxR = curH / 2f - dp(1.5f)
        val r = min(camR + dp(5f), maxR)
        val t = SystemClock.uptimeMillis()
        // grow in when the camera first turns on
        val intro = ((t - camRingStart) / 400f).coerceIn(0f, 1f)
        val rr = r * (0.6f + 0.4f * intro)
        val spin = (t % 1200L) / 1200f * 360f
        val pulse = 0.5f + 0.5f * sin(t / 260f)

        paint.style = Paint.Style.STROKE
        // soft pulsing halo
        paint.strokeWidth = dp(4f)
        paint.color = green
        paint.alpha = (60 + 70 * pulse).toInt()
        c.drawCircle(cx, cy, rr, paint)
        // two arcs spinning in opposite directions
        paint.strokeWidth = dp(2.2f)
        paint.alpha = 255
        r2.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawArc(r2, spin, 110f, false, paint)
        c.drawArc(r2, spin + 180f, 110f, false, paint)
        val ri = rr - dp(3.2f)
        if (ri > camR) {
            paint.strokeWidth = dp(1.4f)
            paint.alpha = 180
            r2.set(cx - ri, cy - ri, cx + ri, cy + ri)
            c.drawArc(r2, -spin * 1.4f, 70f, false, paint)
            c.drawArc(r2, -spin * 1.4f + 180f, 70f, false, paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    // ---------------- pet moods ----------------

    private fun updateMood(now: Long) {
        if (nightCheckedAt == 0L || now - nightCheckedAt > 30_000L) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            night = h >= 23 || h < 6
            nightCheckedAt = now
        }
        val sleeping = night && now - lastMove > 10_000L
        if (wasSleeping && !sleeping) surprisedUntil = now + 1600L // woke up!
        wasSleeping = sleeping
        mood = when {
            now < dizzyUntil -> Mood.DIZZY
            now < surprisedUntil -> Mood.SURPRISED
            sleeping -> Mood.SLEEPING
            charging -> Mood.HAPPY
            battery in 1..15 -> Mood.TIRED
            else -> Mood.NORMAL
        }
    }

    private fun drawEyes(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val cy = curH / 2f
        val r = curH * 0.3f
        val off = curW * 0.3f
        val t = now - blinkStart
        val blinkLen = if (mood == Mood.TIRED) 520L else 180L
        val open = if (t in 0L..blinkLen) abs(t - blinkLen / 2) / (blinkLen / 2f) else 1f
        val oy = r * max(open, 0.12f)

        for (sx in floatArrayOf(-1f, 1f)) {
            val ex = width / 2f + sx * off
            paint.color = Color.WHITE
            paint.strokeCap = Paint.Cap.ROUND
            when (mood) {
                Mood.HAPPY -> { // ^ ^
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = r * 0.42f
                    r2.set(ex - r * 0.85f, cy - r * 0.5f, ex + r * 0.85f, cy + r * 1.2f)
                    c.drawArc(r2, 200f, 140f, false, paint)
                }
                Mood.SLEEPING -> { // closed ‿ ‿
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = r * 0.3f
                    r2.set(ex - r * 0.85f, cy - r * 1.2f, ex + r * 0.85f, cy + r * 0.5f)
                    c.drawArc(r2, 20f, 140f, false, paint)
                }
                Mood.DIZZY -> { // spinning spirals
                    paint.style = Paint.Style.FILL
                    c.drawCircle(ex, cy, r, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = r * 0.17f
                    paint.color = Color.BLACK
                    val rot = (now % 700L) / 700f * 6.2832f * sx
                    path.reset()
                    for (i in 0..40) {
                        val th = i / 40f * 12.566f
                        val rad = r * 0.82f * th / 12.566f
                        val px = ex + rad * cos(th + rot)
                        val py = cy + rad * sin(th + rot)
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    c.drawPath(path, paint)
                }
                Mood.SURPRISED -> { // big eyes, tiny pupils
                    paint.style = Paint.Style.FILL
                    val big = min(r * 1.2f, curH / 2f - dp(1f))
                    c.drawCircle(ex, cy, big, paint)
                    paint.color = Color.BLACK
                    c.drawCircle(ex, cy, big * 0.3f, paint)
                }
                Mood.TIRED -> { // droopy half-closed lids
                    paint.style = Paint.Style.FILL
                    r2.set(ex - r, cy - oy, ex + r, cy + oy)
                    c.drawOval(r2, paint)
                    paint.color = Color.BLACK
                    if (open > 0.45f) c.drawCircle(ex + lookX * r * 0.4f, cy + lookY * r * 0.45f, r * 0.45f, paint)
                    c.drawRect(ex - r - 2f, cy - r - 2f, ex + r + 2f, cy - r * 0.05f, paint)
                }
                Mood.NORMAL -> {
                    paint.style = Paint.Style.FILL
                    r2.set(ex - r, cy - oy, ex + r, cy + oy)
                    c.drawOval(r2, paint)
                    if (open > 0.45f) {
                        paint.color = Color.BLACK
                        c.drawCircle(ex + lookX * r * 0.45f, cy + lookY * r * 0.45f, r * 0.5f, paint)
                    }
                }
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL

        if (mood == Mood.SLEEPING) { // floating z's
            for (k in 0..1) {
                val ph = ((now + k * 1000L) % 2000L) / 2000f
                zP.textSize = dp(7f) + ph * dp(3f)
                zP.alpha = ((1f - ph) * 255).toInt()
                val zx = width / 2f + off + r + dp(4f) + ph * dp(4f)
                val zy = cy + r - ph * curH * 0.45f
                c.drawText("z", zx, zy, zP)
            }
        }
    }

    /** Rim of the pill glows in the album-art colour and pulses with the music. */
    private fun drawGlow(c: Canvas, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = glowColor
        paint.strokeWidth = dp(6f)
        paint.alpha = (35 + 120 * beat).toInt().coerceIn(0, 255)
        r2.set(rect)
        r2.inset(dp(3f), dp(3f))
        val rr = (radius - dp(3f)).coerceAtLeast(0f)
        c.drawRoundRect(r2, rr, rr, paint)
        paint.color = glowColor
        paint.strokeWidth = dp(1.8f)
        paint.alpha = (110 + 145 * beat).toInt().coerceIn(0, 255)
        r2.set(rect)
        r2.inset(dp(1.2f), dp(1.2f))
        val r3 = (radius - dp(1.2f)).coerceAtLeast(0f)
        c.drawRoundRect(r2, r3, r3, paint)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
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
            Triple("🎮", "Game", Action.GAME) to false,
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
                Mode.EXPANDED, Mode.GAME -> {}
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
            if (mode == Mode.GAME) return
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
                Mode.GAME -> if (velocityY < 0 && abs(velocityY) > abs(velocityX)) collapse()
                else -> if (velocityY > 0) expand()
            }
            return true
        }
    })

    private fun tapExpanded(x: Float, y: Float) {
        bumpAutoCollapse()
        val hit = buttons.firstOrNull { it.first.contains(x, y) }
        if (hit != null && hit.second == Action.GAME) {
            haptic()
            startGame()
        } else if (hit != null) {
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
        if (mode == Mode.GAME && event.actionMasked == MotionEvent.ACTION_DOWN) gameTap()
        return gd.onTouchEvent(event)
    }

    // =====================================================================
    // Island Jump — tiny runner game
    // =====================================================================

    var gameBest = 0
    private class Ob(var x: Float, val w: Float, val h: Float, val color: Int)
    private val obs = ArrayList<Ob>()
    private var gY = 0f          // player height above ground
    private var gV = 0f          // vertical speed (up is +)
    private var gScore = 0
    private var gStarted = false
    private var gOver = false
    private var gOverAt = 0L
    private var gLast = 0L
    private var gSpawnIn = 0f
    private var gDist = 0f
    private var gNewBest = false
    private val obColors = intArrayOf(0xFF7CF29C.toInt(), 0xFFFFA94D.toInt(), 0xFF74C0FC.toInt(), 0xFFF783AC.toInt())

    private fun startGame() {
        removeCallbacks(autoCollapse)
        resetGame()
        go(Mode.GAME)
        bumpGameIdle()
    }

    private fun resetGame() {
        obs.clear()
        gY = 0f; gV = 0f; gScore = 0; gDist = 0f
        gStarted = false; gOver = false; gNewBest = false
        gSpawnIn = 0.6f
        gLast = SystemClock.uptimeMillis()
    }

    private fun bumpGameIdle() {
        removeCallbacks(autoCollapse)
        postDelayed(autoCollapse, 30_000L)
    }

    private fun gameTap() {
        bumpGameIdle()
        val now = SystemClock.uptimeMillis()
        if (gOver) {
            if (now - gOverAt > 600L) { resetGame(); gStarted = true; jump() }
            return
        }
        gStarted = true
        jump()
    }

    private fun jump() {
        if (gY <= 0.5f) {
            gV = dp(640f)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun gSpeed() = min(dp(190f) + gScore * dp(7f), dp(430f))

    private fun stepGame(now: Long) {
        val dt = ((now - gLast) / 1000f).coerceIn(0f, 0.05f)
        gLast = now
        if (!gStarted || gOver || contentAlpha < 1f) return

        // player physics
        gV -= dp(2200f) * dt
        gY = max(0f, gY + gV * dt)
        if (gY == 0f && gV < 0f) gV = 0f

        // move obstacles
        val speed = gSpeed()
        gDist += speed * dt
        val playerX = left0 + dp(56f)
        val pr = dp(13f)
        val ground = curH - dp(34f)
        val py = ground - pr - gY
        val iter = obs.iterator()
        while (iter.hasNext()) {
            val o = iter.next()
            val before = o.x + o.w
            o.x -= speed * dt
            if (before >= playerX - pr && o.x + o.w < playerX - pr) {
                gScore++
                if (gScore % 10 == 0) haptic()
            }
            if (o.x + o.w < left0) iter.remove()
            // collision (circle vs box, a little forgiving)
            val nx = playerX.coerceIn(o.x, o.x + o.w)
            val ny = py.coerceIn(ground - o.h, ground)
            val ddx = playerX - nx
            val ddy = py - ny
            if (ddx * ddx + ddy * ddy < (pr * 0.82f) * (pr * 0.82f)) {
                gOver = true
                gOverAt = now
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (gScore > gameBest) {
                    gameBest = gScore
                    gNewBest = true
                    host.onGameBest(gameBest)
                }
            }
        }

        // spawn new obstacles
        gSpawnIn -= dt
        if (gSpawnIn <= 0f) {
            val w = dp(12f) + Random.nextFloat() * dp(12f)
            val h = dp(16f) + Random.nextFloat() * dp(20f)
            obs.add(Ob(right0, w, h, obColors[Random.nextInt(obColors.size)]))
            val gap = 0.75f + Random.nextFloat() * 0.9f
            gSpawnIn = gap * (dp(230f) / speed).coerceIn(0.55f, 1.2f)
        }
    }

    private fun drawGame(c: Canvas) {
        val ground = curH - dp(34f)
        val playerX = left0 + dp(56f)
        val pr = dp(13f)
        val py = ground - pr - gY

        // twinkly background dots (parallax)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF3A3A44.toInt()
        for (i in 0 until 9) {
            val span = curW
            val sx = left0 + ((i * 97f * density - gDist * 0.25f) % span + span) % span
            val sy = dp(70f) + (i * 37 % 60) * density
            c.drawCircle(sx, sy, dp(1.4f), paint)
        }

        // ground
        paint.color = 0xFF55555C.toInt()
        c.drawRect(left0 + dp(18f), ground, right0 - dp(18f), ground + dp(2f), paint)
        // ground dashes moving
        paint.color = 0xFF3A3A44.toInt()
        val step = dp(28f)
        var gx = left0 + dp(18f) - (gDist % step)
        while (gx < right0 - dp(24f)) {
            if (gx > left0 + dp(18f)) c.drawRect(gx, ground + dp(8f), gx + dp(8f), ground + dp(10f), paint)
            gx += step
        }

        // obstacles
        for (o in obs) {
            paint.color = o.color
            r2.set(o.x, ground - o.h, o.x + o.w, ground)
            c.drawRoundRect(r2, dp(4f), dp(4f), paint)
        }

        // player: the pet blob
        val squash = if (gY == 0f && gStarted && !gOver) 1f + 0.06f * sin(gDist / dp(8f)) else 1f
        paint.color = Color.WHITE
        r2.set(playerX - pr * squash, py - pr / squash, playerX + pr * squash, py + pr)
        c.drawOval(r2, paint)
        paint.color = Color.BLACK
        if (gOver) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.8f)
            for (ex in floatArrayOf(playerX - pr * 0.1f, playerX + pr * 0.45f)) {
                val ey = py - pr * 0.2f
                val k = pr * 0.17f
                c.drawLine(ex - k, ey - k, ex + k, ey + k, paint)
                c.drawLine(ex - k, ey + k, ex + k, ey - k, paint)
            }
            paint.style = Paint.Style.FILL
        } else {
            c.drawCircle(playerX + pr * 0.05f, py - pr * 0.2f, pr * 0.16f, paint)
            c.drawCircle(playerX + pr * 0.5f, py - pr * 0.2f, pr * 0.16f, paint)
        }

        // score (left) + best (right), kept clear of the camera
        bigP.textSize = dp(30f)
        c.drawText("$gScore", left0 + dp(22f), dp(46f), bigP)
        bigP.textSize = dp(40f)
        subP.textAlign = Paint.Align.RIGHT
        c.drawText("BEST $gameBest", right0 - dp(22f), dp(40f), subP)
        subP.textAlign = Paint.Align.LEFT

        val cx = (left0 + right0) / 2f
        if (!gStarted) {
            titleP.textAlign = Paint.Align.CENTER
            c.drawText("Tap to jump", cx, dp(96f), titleP)
            titleP.textAlign = Paint.Align.LEFT
            capP.color = 0xFF8A8A92.toInt()
            c.drawText("swipe up to exit", cx, dp(114f), capP)
            capP.color = 0xFFA8A8B0.toInt()
        } else if (gOver) {
            titleP.textAlign = Paint.Align.CENTER
            c.drawText(if (gNewBest) "New best! 🎉" else "Game over", cx, dp(92f), titleP)
            titleP.textAlign = Paint.Align.LEFT
            capP.color = 0xFF8A8A92.toInt()
            c.drawText("tap to play again  •  swipe up to exit", cx, dp(110f), capP)
            capP.color = 0xFFA8A8B0.toInt()
        }
    }
}
