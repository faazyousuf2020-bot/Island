package com.faaz.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Full-screen game layer.
 * Pull the island's eyes down → let go → the blob drops, bounces and splats over the
 * whole screen → pick a game: Dodge (drag), Tilt Maze (tilt), Balance (tilt).
 */
@SuppressLint("ViewConstructor")
class DodgeView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun onDodgeClosed()
        fun onBest(game: Int, best: Int)
        fun setDodgeTouchable(touchable: Boolean)
    }

    companion object {
        const val DODGE = 0
        const val MAZE = 1
        const val BALANCE = 2
    }

    private enum class St { PULL, SNAPBACK, DROP, FILL, MENU, PLAY, DEAD, OVER, CLOSE }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** best per game: dodge = points, maze = levels cleared, balance = tenths of a second */
    val bests = IntArray(3)
    private var game = DODGE
    private var st = St.PULL
    private var closed = false
    private val pill = RectF()
    fun setPill(r: RectF) = pill.set(r)

    // ---------------- blob (pull / drop / fill) ----------------
    private val blobR get() = dp(30f)
    private val launchDist get() = dp(150f)
    private var fx = 0f
    private var fy = 0f
    private var bx = 0f
    private var by = 0f
    private var bvy = 0f
    private var bounces = 0
    private var squashAt = -1000L
    private var phaseStart = 0L
    private var snapFromX = 0f
    private var snapFromY = 0f
    private var fillCx = 0f
    private var fillCy = 0f

    // shared player position (used by the close animation too)
    private var px = 0f
    private var py = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var gameT = 0f
    private var newBest = false
    private var deadAt = 0L
    private var lastFrame = 0L

    // ---------------- dodge ----------------
    private class Hz(
        val type: Int, var x: Float, var y: Float, var vx: Float, var vy: Float,
        val r: Float, val horiz: Boolean = false, val life: Float = 99f
    ) {
        var age = 0f
        var dead = false
    }

    private val hz = ArrayList<Hz>()
    private var pvx = 0f
    private var pvy = 0f
    private val pr get() = dp(20f)
    private var bonus = 0
    private var score = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var nextMeteor = 0f
    private var nextLaser = 0f
    private var nextBouncer = 0f
    private var nextChaser = 0f
    private var nextStar = 0f
    private val popups = ArrayList<Triple<Float, Float, Long>>()

    // ---------------- tilt maze ----------------
    private var mLevel = 1
    private var mCols = 0
    private var mRows = 0
    private var mCell = 0f
    private var mLeft = 0f
    private var mTop = 0f
    private var mGoalCol = 0
    private var mWallsH = Array(1) { BooleanArray(1) }
    private var mWallsV = Array(1) { BooleanArray(1) }
    private val mWalls = ArrayList<RectF>()
    private var mVx = 0f
    private var mVy = 0f
    private var mTimeLeft = 0f
    private var mClearAt = 0L
    private var mLastBump = 0L

    // ---------------- balance ----------------
    private var bAngle = 0f
    private var bS = 0f        // ball position along the beam (0 = middle)
    private var bV = 0f
    private var bWind = 0f
    private var bWindTarget = 0f
    private var bNextGust = 0f
    private var bGustEnd = 0f
    private var bFallX = 0f
    private var bFallY = 0f
    private var bFallVx = 0f
    private var bFallVy = 0f
    private val windStreaks = Array(14) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }

    // ---------------- tilt sensor ----------------
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensorOn = false
    private var gx = 0f
    private var gy = 0f
    private var gx0 = 0f
    private var gy0 = 0f
    private var needCal = true
    private val sensorL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]
            val y = e.values[1]
            if (needCal) {
                gx0 = x; gy0 = y; gx = x; gy = y
                needCal = false
            } else {
                gx += (x - gx) * 0.35f
                gy += (y - gy) * 0.35f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun sensors(on: Boolean) {
        if (on && !sensorOn) {
            val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensorOn = sm.registerListener(sensorL, s, SensorManager.SENSOR_DELAY_GAME)
        } else if (!on && sensorOn) {
            sm.unregisterListener(sensorL)
            sensorOn = false
        }
    }

    /** right-edge-down = +, in m/s² relative to how you held the phone at the start */
    private fun tiltX() = -(gx - gx0)
    /** top-edge-down (towards you) = −, bottom-edge-down = + */
    private fun tiltY() = gy - gy0

    // ---------------- paints ----------------
    private val bg = 0xFF050507.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val dash = DashPathEffect(floatArrayOf(dp(10f), dp(8f)), 0f)
    private val dots = DashPathEffect(floatArrayOf(dp(3f), dp(7f)), 0f)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val r2 = RectF()
    private val againBtn = RectF()
    private val menuBtn = RectF()
    private val closeBtn = RectF()
    private val cards = Array(3) { RectF() }
    private val stars = Array(46) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }

    private val orange = 0xFFFFA94D.toInt()
    private val red = 0xFFFF4D5E.toInt()
    private val cyan = 0xFF4DD9FF.toInt()
    private val pink = 0xFFF783AC.toInt()
    private val gold = 0xFFFFD54F.toInt()
    private val green = 0xFF7CF29C.toInt()
    private val violet = 0xFF9D8CFF.toInt()

    private fun maxR() = hypot(width.toFloat(), height.toFloat())
    private fun now() = SystemClock.uptimeMillis()
    private fun haptic(type: Int = HapticFeedbackConstants.VIRTUAL_KEY) = performHapticFeedback(type)

    // =====================================================================
    // entry points from the island
    // =====================================================================

    fun startPull(x: Float, y: Float) {
        st = St.PULL
        fx = x; fy = y
        invalidate()
    }

    fun movePull(x: Float, y: Float) {
        if (st != St.PULL) return
        val wasReady = pullDist() >= launchDist
        fx = x; fy = y
        if (!wasReady && pullDist() >= launchDist) haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun pullDist() = max(0f, fy - pill.bottom)

    /** @return true if the game launched. */
    fun endPull(x: Float, y: Float): Boolean {
        if (st != St.PULL) return false
        fx = x; fy = y
        return if (pullDist() >= launchDist) {
            startDrop(clampX(fx), max(fy, pill.bottom + blobR))
            true
        } else {
            st = St.SNAPBACK
            snapFromX = fx; snapFromY = max(fy, pill.centerY())
            phaseStart = now()
            invalidate()
            false
        }
    }

    /** Start without pulling (from the 🎮 button): blob drops out of the camera. */
    fun launchFromPill() {
        startDrop(pill.centerX(), pill.bottom + blobR * 0.5f)
    }

    private fun clampX(x: Float) = x.coerceIn(blobR, max(blobR, width - blobR))

    private fun startDrop(x: Float, y: Float) {
        host.setDodgeTouchable(true)
        st = St.DROP
        bx = x; by = y; bvy = 0f; bounces = 0
        haptic(HapticFeedbackConstants.LONG_PRESS)
        lastFrame = now()
        invalidate()
    }

    private fun startFill() {
        st = St.FILL
        fillCx = bx; fillCy = by
        px = bx; py = by
        phaseStart = now()
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun openMenu() {
        sensors(false)
        st = St.MENU
        px = width / 2f; py = height / 2f
    }

    private fun startPlay() {
        st = St.PLAY
        gameT = 0f
        newBest = false
        lastFrame = now()
        lookX = 0f; lookY = 0f
        when (game) {
            DODGE -> startDodge()
            MAZE -> startMaze()
            BALANCE -> startBalance()
        }
        needCal = true
        sensors(game != DODGE)
    }

    fun close() {
        if (st == St.CLOSE || closed) return
        if (st == St.DROP) { px = bx; py = by }
        if (st == St.FILL) { px = fillCx; py = fillCy }
        sensors(false)
        host.setDodgeTouchable(false)
        st = St.CLOSE
        phaseStart = now()
        invalidate()
    }

    /** Remove immediately (screen off etc). */
    fun kill() {
        if (closed) return
        closed = true
        sensors(false)
        host.onDodgeClosed()
    }

    private fun finish() {
        if (closed) return
        closed = true
        sensors(false)
        post { host.onDodgeClosed() }
    }

    override fun onDetachedFromWindow() {
        sensors(false)
        super.onDetachedFromWindow()
    }

    private fun die() {
        if (st != St.PLAY) return
        st = St.DEAD
        deadAt = now()
        haptic(HapticFeedbackConstants.LONG_PRESS)
        val result = when (game) {
            DODGE -> score
            MAZE -> mLevel - 1
            else -> (gameT * 10).toInt()
        }
        if (result > bests[game]) {
            bests[game] = result
            newBest = true
            host.onBest(game, result)
        }
    }

    // =====================================================================
    // frame loop
    // =====================================================================

    override fun onDraw(c: Canvas) {
        if (closed) return
        val now = now()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.033f)
        lastFrame = now
        when (st) {
            St.PULL -> drawPull(c, fx, max(fy, pill.centerY()), true)
            St.SNAPBACK -> {
                val f = ((now - phaseStart) / 220f).coerceIn(0f, 1f)
                val e = 1f - (1f - f) * (1f - f)
                val x = snapFromX + (pill.centerX() - snapFromX) * e
                val y = snapFromY + (pill.centerY() - snapFromY) * e
                if (f < 1f) drawPull(c, x, y, false, 1f - e) else { finish(); return }
            }
            St.DROP -> { stepDrop(dt, now); drawDrop(c, now) }
            St.FILL -> drawFill(c, now)
            St.MENU -> { c.drawColor(bg); drawBackdrop(c, now); drawMenu(c, now); drawCloseBtn(c) }
            St.PLAY, St.DEAD, St.OVER -> {
                if (st == St.PLAY) when (game) {
                    DODGE -> stepDodge(dt)
                    MAZE -> stepMaze(dt, now)
                    BALANCE -> stepBalance(dt)
                }
                if (st == St.DEAD && game == BALANCE) stepFall(dt)
                if (st == St.DEAD && now - deadAt > 800L) st = St.OVER
                drawGame(c, now)
            }
            St.CLOSE -> if (!drawClose(c, now)) { finish(); return }
        }
        postInvalidateOnAnimation()
    }

    // ---------------- pull ----------------

    private fun drawPull(c: Canvas, x0: Float, y0: Float, live: Boolean, shrink: Float = 1f) {
        val x = clampX(x0)
        val pcx = pill.centerX()
        val top = pill.centerY()
        val rad = pill.height() / 2f
        c.drawRoundRect(pill, rad, rad, black)

        val dist = max(0f, y0 - pill.bottom)
        val R = blobR * (0.45f + 0.55f * shrink).coerceAtMost(1f) * (1f + min(dist, launchDist) / launchDist * 0.12f)
        val wTop = pill.height() * 0.42f
        val wBot = max(dp(5f), R * 0.72f - dist * 0.06f)
        val span = y0 - top
        path.reset()
        path.moveTo(pcx - wTop, top)
        path.cubicTo(pcx - wTop, top + span * 0.45f, x - wBot, y0 - span * 0.45f, x - wBot, y0)
        path.lineTo(x + wBot, y0)
        path.cubicTo(x + wBot, y0 - span * 0.45f, pcx + wTop, top + span * 0.45f, pcx + wTop, top)
        path.close()
        c.drawPath(path, black)
        c.drawCircle(x, y0, R, black)

        if (live && dist >= launchDist) {
            val pulse = 0.5f + 0.5f * sin(now() / 120f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = green
            paint.alpha = (140 + 115 * pulse).toInt()
            c.drawCircle(x, y0, R + dp(5f) + pulse * dp(3f), paint)
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }
        val lx = ((pcx - x) / dp(120f)).coerceIn(-1f, 1f)
        drawEyes(c, x, y0, R, lx, -1f, wide = dist > launchDist * 0.6f)
    }

    // ---------------- drop + bounce ----------------

    private fun floorY() = height - dp(70f) - blobR

    private fun stepDrop(dt: Float, now: Long) {
        bvy += dp(2800f) * dt
        by += bvy * dt
        bx += (width / 2f - bx) * min(1f, dt * 2.5f)
        if (by >= floorY()) {
            by = floorY()
            if (bounces < 2) {
                bvy = -abs(bvy) * (if (bounces == 0) 0.55f else 0.4f)
                bounces++
                squashAt = now
                haptic()
            } else {
                startFill()
            }
        }
    }

    private fun drawDrop(c: Canvas, now: Long) {
        val t = (now - squashAt) / 160f
        val s = (if (t in 0f..1f) 1f - t else 0f) * 0.35f
        val rx = blobR * (1f + s)
        val ry = blobR * (1f - s)
        r2.set(bx - rx, by - ry * 2f + blobR, bx + rx, by + blobR)
        c.drawOval(r2, black)
        drawEyes(c, bx, r2.centerY(), blobR, 0f, if (bvy > 0) 0.8f else -0.6f, wide = bvy > 0)
    }

    // ---------------- splat fill ----------------

    private fun drawFill(c: Canvas, now: Long) {
        val f = ((now - phaseStart) / 560f).coerceIn(0f, 1f)
        val e = 1f - (1f - f) * (1f - f) * (1f - f)
        val r = blobR + (maxR() - blobR) * e
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawCircle(fillCx, fillCy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f) * (1f - f) + dp(1f)
        paint.color = Color.WHITE
        paint.alpha = ((1f - f) * 200).toInt()
        c.drawCircle(fillCx, fillCy, r, paint)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        val pop = 1f + 1.2f * sin(f * Math.PI.toFloat())
        drawPlayerBody(c, fillCx, fillCy, pr * pop, 0f, 0f, false)
        if (f >= 1f) openMenu()
    }

    // ---------------- close ----------------

    private fun drawClose(c: Canvas, now: Long): Boolean {
        val f = ((now - phaseStart) / 430f).coerceIn(0f, 1f)
        val e = f * f
        val cx = px + (pill.centerX() - px) * e
        val cy = py + (pill.centerY() - py) * e
        val r = maxR() + (pill.height() / 2f - maxR()) * e
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawCircle(cx, cy, r, paint)
        drawEyes(c, cx, cy, blobR * (1f - 0.5f * e), 0f, -1f, false)
        return f < 1f
    }

    // =====================================================================
    // menu
    // =====================================================================

    private val names = arrayOf("Dodge", "Tilt Maze", "Balance")
    private val icons = arrayOf("🌠", "🌀", "⚖️")
    private val blurbs = arrayOf("Drag to dodge everything", "Tilt to roll home to the camera", "Tilt to keep the blob on the beam")
    private val accents get() = intArrayOf(orange, violet, cyan)

    private fun bestLabel(g: Int): String {
        val b = bests[g]
        return when (g) {
            DODGE -> "best $b"
            MAZE -> "best lvl $b"
            else -> String.format(Locale.US, "best %.1fs", b / 10f)
        }
    }

    private fun drawMenu(c: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        // blob peeking above the cards
        val bob = sin(now / 400f) * dp(4f)
        drawPlayerBody(c, w / 2f, h * 0.27f + bob, pr * 1.6f, sin(now / 900f) * 0.6f, 0.3f, false)

        txt.textAlign = Paint.Align.CENTER
        txt.color = Color.WHITE
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.textSize = dp(22f)
        c.drawText("Pick a game", w / 2f, h * 0.27f + pr * 1.6f + dp(46f), txt)

        val cw = min(w - dp(40f), dp(360f))
        val ch = dp(76f)
        var top = h * 0.27f + pr * 1.6f + dp(72f)
        for (i in 0 until 3) {
            val r = cards[i]
            r.set(w / 2f - cw / 2f, top, w / 2f + cw / 2f, top + ch)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF16161C.toInt()
            c.drawRoundRect(r, dp(20f), dp(20f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = accents[i]
            paint.alpha = 110
            c.drawRoundRect(r, dp(20f), dp(20f), paint)
            paint.alpha = 255
            paint.style = Paint.Style.FILL

            txt.textAlign = Paint.Align.CENTER
            txt.textSize = dp(28f)
            c.drawText(icons[i], r.left + dp(38f), r.centerY() + dp(10f), txt)
            txt.textAlign = Paint.Align.LEFT
            txt.typeface = Typeface.DEFAULT_BOLD
            txt.textSize = dp(17f)
            txt.color = Color.WHITE
            c.drawText(names[i], r.left + dp(72f), r.centerY() - dp(4f), txt)
            txt.typeface = Typeface.DEFAULT
            txt.textSize = dp(12.5f)
            txt.color = 0xFF9A9AA2.toInt()
            c.drawText(blurbs[i], r.left + dp(72f), r.centerY() + dp(16f), txt)
            txt.textAlign = Paint.Align.RIGHT
            txt.color = accents[i]
            txt.textSize = dp(12f)
            c.drawText(bestLabel(i), r.right - dp(18f), r.top + dp(24f), txt)
            top += ch + dp(14f)
        }
        txt.textAlign = Paint.Align.CENTER
        txt.color = 0xFF6A6A72.toInt()
        txt.textSize = dp(12f)
        c.drawText("Tilt games: hold the phone comfortably — that's 'level'", w / 2f, top + dp(18f), txt)
    }

    // =====================================================================
    // game 1: DODGE
    // =====================================================================

    private fun startDodge() {
        hz.clear(); popups.clear()
        px = if (fillCx > 0f) fillCx else width / 2f
        py = height * 0.68f
        pvx = 0f; pvy = 0f
        bonus = 0; score = 0
        nextMeteor = 0.8f; nextLaser = 4f; nextBouncer = 9f; nextChaser = 20f; nextStar = 2.5f
    }

    private fun stepDodge(dt: Float) {
        gameT += dt
        score = (gameT * 10).toInt() + bonus
        val k = 1f + gameT / 25f
        val w = width.toFloat()
        val h = height.toFloat()

        if (gameT >= nextMeteor) {
            val r = dp(9f) + Random.nextFloat() * dp(13f)
            val speed = (dp(240f) + Random.nextFloat() * dp(150f)) * min(1.9f, 0.85f + 0.15f * k)
            hz.add(Hz(0, Random.nextFloat() * w, -r, (Random.nextFloat() - 0.5f) * dp(140f), speed, r))
            nextMeteor = gameT + max(0.2f, 0.85f / k)
        }
        if (gameT >= nextLaser) {
            val horiz = Random.nextBoolean()
            val aim = Random.nextFloat() < 0.6f
            val pos = if (horiz) {
                (if (aim) py + (Random.nextFloat() - 0.5f) * dp(120f) else dp(120f) + Random.nextFloat() * (h - dp(200f)))
                    .coerceIn(dp(110f), h - dp(40f))
            } else {
                (if (aim) px + (Random.nextFloat() - 0.5f) * dp(120f) else Random.nextFloat() * w)
                    .coerceIn(dp(20f), w - dp(20f))
            }
            hz.add(Hz(1, if (horiz) 0f else pos, if (horiz) pos else 0f, 0f, 0f, dp(9f), horiz, 1.3f))
            nextLaser = gameT + max(1.3f, 3.6f / k)
        }
        if (gameT >= nextBouncer) {
            if (hz.count { it.type == 2 } < min(4, 1 + (gameT / 20f).toInt())) {
                val a = Random.nextFloat() * 6.283f
                val sp = dp(230f) * min(1.6f, k)
                hz.add(Hz(2, Random.nextFloat() * w, dp(130f), cos(a) * sp, abs(sin(a)) * sp + dp(60f), dp(15f), life = 8f))
            }
            nextBouncer = gameT + 5f
        }
        if (gameT >= nextChaser) {
            val side = Random.nextInt(4)
            val sx = when (side) { 0 -> -dp(20f); 1 -> w + dp(20f); else -> Random.nextFloat() * w }
            val sy = when (side) { 2 -> dp(100f); 3 -> h + dp(20f); else -> dp(120f) + Random.nextFloat() * (h - dp(200f)) }
            hz.add(Hz(3, sx, sy, 0f, 0f, dp(14f), life = 6.5f))
            nextChaser = gameT + max(4f, 9f / k * 1.4f)
        }
        if (gameT >= nextStar) {
            hz.add(Hz(4, dp(30f) + Random.nextFloat() * (w - dp(60f)), -dp(20f), 0f, dp(130f), dp(13f)))
            nextStar = gameT + 3.5f
        }

        val hitR = pr * 0.78f
        for (o in hz) {
            o.age += dt
            when (o.type) {
                0 -> { o.x += o.vx * dt; o.y += o.vy * dt; if (o.y > h + o.r * 2) o.dead = true }
                1 -> if (o.age > o.life) o.dead = true
                2 -> {
                    o.x += o.vx * dt; o.y += o.vy * dt
                    if (o.x < o.r) { o.x = o.r; o.vx = abs(o.vx) }
                    if (o.x > w - o.r) { o.x = w - o.r; o.vx = -abs(o.vx) }
                    if (o.y < dp(100f)) { o.y = dp(100f); o.vy = abs(o.vy) }
                    if (o.y > h - o.r) { o.y = h - o.r; o.vy = -abs(o.vy) }
                    if (o.age > o.life) o.dead = true
                }
                3 -> {
                    val dx = px - o.x; val dy = py - o.y
                    val d = max(1f, sqrt(dx * dx + dy * dy))
                    val sp = dp(150f) * min(1.5f, 0.8f + 0.2f * k)
                    o.vx += (dx / d * sp - o.vx) * min(1f, dt * 1.6f)
                    o.vy += (dy / d * sp - o.vy) * min(1f, dt * 1.6f)
                    o.x += o.vx * dt; o.y += o.vy * dt
                    if (o.age > o.life) o.dead = true
                }
                4 -> { o.y += o.vy * dt; o.x += sin(o.age * 3f) * dp(40f) * dt; if (o.y > h + o.r) o.dead = true }
            }
            if (o.dead) continue
            val hit = when (o.type) {
                1 -> o.age in 0.9f..1.25f && (if (o.horiz) abs(py - o.y) else abs(px - o.x)) < o.r + hitR
                2 -> o.age < o.life - 0.6f && near(o, hitR)
                3 -> o.age > 0.3f && o.age < o.life - 0.4f && near(o, hitR)
                4 -> false
                else -> near(o, hitR)
            }
            if (o.type == 4 && near(o, pr)) {
                o.dead = true
                bonus += 50
                popups.add(Triple(o.x, o.y, now()))
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (hit) { die(); break }
        }
        hz.removeAll { it.dead }
        lookX += ((pvx / dp(600f)).coerceIn(-1f, 1f) - lookX) * min(1f, dt * 10f)
        lookY += ((pvy / dp(600f)).coerceIn(-1f, 1f) - lookY) * min(1f, dt * 10f)
        pvx *= 0.85f; pvy *= 0.85f
    }

    private fun near(o: Hz, extra: Float): Boolean {
        val dx = px - o.x; val dy = py - o.y
        val rr = o.r + extra
        return dx * dx + dy * dy < rr * rr
    }

    private fun drawDodge(c: Canvas, now: Long) {
        for (o in hz) drawHazard(c, o)
        drawPlayerBody(c, px, py, pr, lookX, lookY, st != St.PLAY)
        txt.textAlign = Paint.Align.CENTER
        txt.textSize = dp(16f)
        txt.typeface = Typeface.DEFAULT_BOLD
        popups.removeAll { now - it.third > 700L }
        for (p in popups) {
            val f = (now - p.third) / 700f
            txt.color = gold
            txt.alpha = ((1f - f) * 255).toInt()
            c.drawText("+50", p.first, p.second - f * dp(30f), txt)
        }
        txt.alpha = 255
    }

    private fun drawHazard(c: Canvas, o: Hz) {
        paint.style = Paint.Style.FILL
        when (o.type) {
            0 -> {
                val len = sqrt(o.vx * o.vx + o.vy * o.vy).coerceAtLeast(1f)
                for (i in 3 downTo 1) {
                    paint.color = orange
                    paint.alpha = 40 * (4 - i)
                    c.drawCircle(o.x - o.vx / len * o.r * 1.1f * i, o.y - o.vy / len * o.r * 1.1f * i, o.r * (1f - i * 0.18f), paint)
                }
                paint.alpha = 255
                paint.color = orange
                c.drawCircle(o.x, o.y, o.r, paint)
                paint.color = 0xFFFFE0B2.toInt()
                c.drawCircle(o.x - o.r * 0.3f, o.y - o.r * 0.3f, o.r * 0.35f, paint)
            }
            1 -> {
                val w = width.toFloat(); val h = height.toFloat()
                if (o.age < 0.9f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2f)
                    paint.pathEffect = dash
                    paint.color = red
                    paint.alpha = (90 + 140 * (0.5f + 0.5f * sin(o.age * 30f))).toInt()
                    if (o.horiz) c.drawLine(0f, o.y, w, o.y, paint) else c.drawLine(o.x, 0f, o.x, h, paint)
                    paint.pathEffect = null
                } else if (o.age < 1.25f) {
                    val f = 1f - (o.age - 0.9f) / 0.35f
                    paint.color = red
                    paint.alpha = (120 + 135 * f).toInt()
                    val t = o.r * (0.7f + 0.3f * f)
                    if (o.horiz) c.drawRect(0f, o.y - t, w, o.y + t, paint) else c.drawRect(o.x - t, 0f, o.x + t, h, paint)
                    paint.color = Color.WHITE
                    val ct = t * 0.3f
                    if (o.horiz) c.drawRect(0f, o.y - ct, w, o.y + ct, paint) else c.drawRect(o.x - ct, 0f, o.x + ct, h, paint)
                }
                paint.alpha = 255
                paint.style = Paint.Style.FILL
            }
            2 -> {
                val fade = ((o.life - o.age) / 0.6f).coerceIn(0f, 1f)
                paint.color = cyan
                paint.alpha = (70 * fade).toInt()
                c.drawCircle(o.x, o.y, o.r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3f)
                paint.alpha = (255 * fade).toInt()
                c.drawCircle(o.x, o.y, o.r, paint)
                paint.alpha = 255
                paint.style = Paint.Style.FILL
            }
            3 -> {
                val ang = atan2(o.vy, o.vx)
                val fade = ((o.life - o.age) / 0.4f).coerceIn(0f, 1f) * (o.age / 0.3f).coerceIn(0f, 1f)
                paint.color = pink
                paint.alpha = (255 * fade).toInt()
                path.reset()
                for (i in 0 until 3) {
                    val a = ang + i * 2.094f
                    val rr = if (i == 0) o.r * 1.4f else o.r
                    val x = o.x + cos(a) * rr
                    val y = o.y + sin(a) * rr
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, paint)
                paint.alpha = 255
            }
            4 -> drawStar(c, o.x, o.y, o.r, o.age * 2f, gold)
        }
    }

    private fun drawStar(c: Canvas, x0: Float, y0: Float, r: Float, rot: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val a = rot + i * 0.6283f - 1.5708f
            val rr = if (i % 2 == 0) r else r * 0.45f
            val x = x0 + cos(a) * rr
            val y = y0 + sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(path, paint)
    }

    // =====================================================================
    // game 2: TILT MAZE — roll the blob back up into the camera
    // =====================================================================

    private fun startMaze() {
        mLevel = 1
        buildMaze()
    }

    private fun mazeBallR() = mCell * 0.27f

    private fun buildMaze() {
        val w = width.toFloat()
        val h = height.toFloat()
        mCols = min(4 + mLevel, 10)
        val top = dp(150f)
        val bottom = h - dp(36f)
        mCell = (w - dp(28f)) / mCols
        mRows = max(5, floor((bottom - top) / mCell).toInt())
        mLeft = (w - mCols * mCell) / 2f
        mTop = top + ((bottom - top) - mRows * mCell) / 2f
        mGoalCol = mCols / 2

        // all walls up, then carve with a depth-first search
        mWallsH = Array(mRows + 1) { BooleanArray(mCols) { true } }
        mWallsV = Array(mRows) { BooleanArray(mCols + 1) { true } }
        val seen = Array(mRows) { BooleanArray(mCols) }
        val stack = ArrayList<Int>()
        val start = (mRows - 1) * mCols + mGoalCol
        seen[mRows - 1][mGoalCol] = true
        stack.add(start)
        val dirs = intArrayOf(0, 1, 2, 3)
        while (stack.isNotEmpty()) {
            val cur = stack[stack.size - 1]
            val r = cur / mCols
            val cc = cur % mCols
            dirs.shuffle()
            var moved = false
            for (d in dirs) {
                val nr = r + when (d) { 0 -> -1; 1 -> 1; else -> 0 }
                val nc = cc + when (d) { 2 -> -1; 3 -> 1; else -> 0 }
                if (nr !in 0 until mRows || nc !in 0 until mCols || seen[nr][nc]) continue
                when (d) {
                    0 -> mWallsH[r][cc] = false
                    1 -> mWallsH[r + 1][cc] = false
                    2 -> mWallsV[r][cc] = false
                    3 -> mWallsV[r][cc + 1] = false
                }
                seen[nr][nc] = true
                stack.add(nr * mCols + nc)
                moved = true
                break
            }
            if (!moved) stack.removeAt(stack.size - 1)
        }
        mWallsH[0][mGoalCol] = false // the exit, right under the camera

        val t = dp(4f)
        mWalls.clear()
        for (r in 0..mRows) for (cc in 0 until mCols) if (mWallsH[r][cc]) {
            val y = mTop + r * mCell
            mWalls.add(RectF(mLeft + cc * mCell - t / 2, y - t / 2, mLeft + (cc + 1) * mCell + t / 2, y + t / 2))
        }
        for (r in 0 until mRows) for (cc in 0..mCols) if (mWallsV[r][cc]) {
            val x = mLeft + cc * mCell
            mWalls.add(RectF(x - t / 2, mTop + r * mCell - t / 2, x + t / 2, mTop + (r + 1) * mCell + t / 2))
        }

        px = mLeft + (mGoalCol + 0.5f) * mCell
        py = mTop + (mRows - 0.5f) * mCell
        mVx = 0f; mVy = 0f
        mTimeLeft = 25f + mRows * mCols / 4f
        mClearAt = 0L
    }

    private fun stepMaze(dt: Float, now: Long) {
        if (mClearAt > 0L) {
            // ball flies up into the camera, then the next level
            px += (pill.centerX() - px) * min(1f, dt * 6f)
            py += (pill.centerY() - py) * min(1f, dt * 6f)
            if (now - mClearAt > 900L) {
                mLevel++
                buildMaze()
                needCal = true
            }
            return
        }
        gameT += dt
        mTimeLeft -= dt
        if (mTimeLeft <= 0f) { mTimeLeft = 0f; die(); return }

        val acc = dp(260f)
        mVx += tiltX() * acc * dt
        mVy += tiltY() * acc * dt
        val damp = 1f - min(0.9f, 1.2f * dt)
        mVx *= damp; mVy *= damp
        val maxV = dp(650f)
        val sp = hypot(mVx, mVy)
        if (sp > maxV) { mVx *= maxV / sp; mVy *= maxV / sp }

        val r = mazeBallR()
        val steps = 4
        for (s in 0 until steps) {
            px += mVx * dt / steps
            py += mVy * dt / steps
            for (wr in mWalls) {
                val cx = px.coerceIn(wr.left, wr.right)
                val cy = py.coerceIn(wr.top, wr.bottom)
                val dx = px - cx
                val dy = py - cy
                val d2 = dx * dx + dy * dy
                if (d2 < r * r && d2 > 1e-4f) {
                    val d = sqrt(d2)
                    val nx = dx / d
                    val ny = dy / d
                    px = cx + nx * r
                    py = cy + ny * r
                    val vn = mVx * nx + mVy * ny
                    if (vn < 0f) {
                        mVx -= 1.35f * vn * nx
                        mVy -= 1.35f * vn * ny
                        if (-vn > dp(180f) && now - mLastBump > 120L) {
                            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            mLastBump = now
                        }
                    }
                }
            }
        }
        px = px.coerceIn(mLeft + r, mLeft + mCols * mCell - r)
        py = py.coerceIn(mTop - mCell, mTop + mRows * mCell - r)

        lookX += ((mVx / dp(400f)).coerceIn(-1f, 1f) - lookX) * min(1f, dt * 8f)
        lookY += ((mVy / dp(400f)).coerceIn(-1f, 1f) - lookY) * min(1f, dt * 8f)

        // reached the exit under the camera?
        if (py < mTop - r * 0.2f) {
            mClearAt = now
            haptic(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun drawMaze(c: Canvas, now: Long) {
        // dotted trail from the exit up to the camera
        val gx = mLeft + (mGoalCol + 0.5f) * mCell
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.pathEffect = dots
        paint.color = green
        paint.alpha = (120 + 100 * (0.5f + 0.5f * sin(now / 250f))).toInt()
        c.drawLine(gx, mTop, pill.centerX(), pill.bottom + dp(6f), paint)
        paint.pathEffect = null
        // exit glow
        paint.style = Paint.Style.FILL
        paint.alpha = 50
        c.drawCircle(gx, mTop + mCell * 0.5f, mCell * 0.42f, paint)
        paint.alpha = 255

        // walls
        paint.style = Paint.Style.FILL
        paint.color = violet
        for (wr in mWalls) c.drawRoundRect(wr, dp(2f), dp(2f), paint)
        paint.strokeCap = Paint.Cap.BUTT

        val r = mazeBallR()
        val scale = if (mClearAt > 0L) (1f - (now - mClearAt) / 900f).coerceIn(0.2f, 1f) else 1f
        drawPlayerBody(c, px, py, r * scale, lookX, lookY, st == St.DEAD || st == St.OVER)

        if (mClearAt > 0L) {
            txt.textAlign = Paint.Align.CENTER
            txt.typeface = Typeface.DEFAULT_BOLD
            txt.textSize = dp(26f)
            txt.color = green
            c.drawText("Level $mLevel ✓", width / 2f, height / 2f, txt)
        }
    }

    // =====================================================================
    // game 3: BALANCE — keep the blob on a tilting beam
    // =====================================================================

    private fun beamLen() = width * (0.82f - min(0.42f, gameT / 120f))
    private fun beamCx() = width / 2f
    private fun beamCy() = height * 0.6f

    private fun startBalance() {
        bAngle = 0f; bS = 0f; bV = 0f
        bWind = 0f; bWindTarget = 0f
        bNextGust = 3f; bGustEnd = 0f
        placeBalanceBall()
    }

    private fun stepBalance(dt: Float) {
        gameT += dt
        val k = 1f + gameT / 30f
        // phone tilt + a little wobble that grows over time
        val wobble = sin(gameT * 1.7f) * 0.05f * min(2f, k - 0.6f)
        bAngle = (tiltX() * 0.085f + wobble).coerceIn(-0.6f, 0.6f)

        if (gameT >= bNextGust) {
            bWindTarget = (if (Random.nextBoolean()) 1f else -1f) * dp(260f + Random.nextFloat() * 260f) * min(2.2f, k)
            bGustEnd = gameT + 1.2f + Random.nextFloat()
            bNextGust = bGustEnd + max(1.2f, 4.5f / k) + Random.nextFloat() * 2f
        }
        if (gameT > bGustEnd) bWindTarget = 0f
        bWind += (bWindTarget - bWind) * min(1f, dt * 3f)

        val g = dp(1700f)
        bV += (g * sin(bAngle) + bWind) * dt
        bV *= 1f - min(0.5f, 0.35f * dt)
        bS += bV * dt
        placeBalanceBall()

        lookX += ((bV / dp(300f)).coerceIn(-1f, 1f) - lookX) * min(1f, dt * 8f)
        lookY = 0.4f
        if (abs(bS) > beamLen() / 2f + pr * 0.2f) {
            bFallX = px; bFallY = py
            bFallVx = bV * cos(bAngle)
            bFallVy = bV * sin(bAngle)
            die()
        }
    }

    private fun placeBalanceBall() {
        val ca = cos(bAngle)
        val sa = sin(bAngle)
        val lift = pr + dp(5f)
        px = beamCx() + ca * bS + sa * lift
        py = beamCy() + sa * bS - ca * lift
    }

    private fun stepFall(dt: Float) {
        bFallVy += dp(2400f) * dt
        bFallX += bFallVx * dt
        bFallY += bFallVy * dt
        px = bFallX; py = bFallY
    }

    private fun drawBalance(c: Canvas, now: Long) {
        val w = width.toFloat()
        // wind streaks
        if (abs(bWind) > dp(30f)) {
            val dir = if (bWind > 0) 1f else -1f
            val strength = (abs(bWind) / dp(600f)).coerceIn(0f, 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            for (s in windStreaks) {
                val speed = dp(500f) + s[2] * dp(400f)
                val len = dp(30f) + s[2] * dp(40f)
                val x = ((s[0] * w + dir * now / 1000f * speed) % (w + len) + (w + len)) % (w + len) - len / 2
                val y = dp(160f) + s[1] * (height - dp(260f))
                paint.alpha = (strength * 120 * (0.4f + s[2])).toInt().coerceAtMost(255)
                c.drawLine(x, y, x - dir * len, y, paint)
            }
            paint.alpha = 255
            paint.strokeCap = Paint.Cap.BUTT
            // arrow hint
            txt.textAlign = Paint.Align.CENTER
            txt.textSize = dp(28f)
            txt.color = Color.WHITE
            txt.alpha = (120 + 135 * strength).toInt().coerceAtMost(255)
            c.drawText(if (dir > 0) "💨→" else "←💨", w / 2f, dp(170f), txt)
            txt.alpha = 255
        }

        // pivot
        val cx = beamCx()
        val cy = beamCy()
        paint.style = Paint.Style.FILL
        paint.color = 0xFF2A2A33.toInt()
        path.reset()
        path.moveTo(cx, cy + dp(4f))
        path.lineTo(cx - dp(26f), cy + dp(46f))
        path.lineTo(cx + dp(26f), cy + dp(46f))
        path.close()
        c.drawPath(path, paint)

        // beam
        val half = beamLen() / 2f
        val ca = cos(bAngle)
        val sa = sin(bAngle)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(10f)
        paint.color = cyan
        c.drawLine(cx - ca * half, cy - sa * half, cx + ca * half, cy + sa * half, paint)
        paint.strokeWidth = dp(3f)
        paint.color = Color.WHITE
        paint.alpha = 140
        c.drawLine(cx - ca * half * 0.96f, cy - sa * half * 0.96f - dp(2f), cx + ca * half * 0.96f, cy + sa * half * 0.96f - dp(2f), paint)
        paint.alpha = 255
        paint.strokeCap = Paint.Cap.BUTT
        // danger ends
        paint.style = Paint.Style.FILL
        paint.color = red
        c.drawCircle(cx - ca * half, cy - sa * half, dp(6f), paint)
        c.drawCircle(cx + ca * half, cy + sa * half, dp(6f), paint)

        val edge = (abs(bS) / half).coerceIn(0f, 1f)
        drawPlayerBody(c, px, py, pr, lookX, lookY, st != St.PLAY, wide = edge > 0.7f)
    }

    // =====================================================================
    // shared drawing
    // =====================================================================

    private fun drawBackdrop(c: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        for (s in stars) {
            paint.color = Color.WHITE
            paint.alpha = (30 + 60 * (0.5f + 0.5f * sin(now / 600f + s[2] * 20f))).toInt()
            c.drawCircle(s[0] * w, (s[1] * h + gameT * dp(12f) * (0.5f + s[2])) % h, dp(1.2f) + s[2] * dp(1f), paint)
        }
        paint.alpha = 255
    }

    private fun drawGame(c: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(bg)

        c.save()
        val shake = if (st == St.DEAD) (1f - ((now - deadAt) / 320f)).coerceIn(0f, 1f) else 0f
        if (shake > 0f) c.translate((Random.nextFloat() - 0.5f) * dp(16f) * shake, (Random.nextFloat() - 0.5f) * dp(16f) * shake)
        drawBackdrop(c, now)
        when (game) {
            DODGE -> drawDodge(c, now)
            MAZE -> drawMaze(c, now)
            BALANCE -> drawBalance(c, now)
        }
        c.restore()

        // HUD (left side, clear of the camera)
        val (big, small) = when (game) {
            DODGE -> "$score" to String.format(Locale.US, "%.1fs  •  best %d", gameT, bests[DODGE])
            MAZE -> "Level $mLevel" to String.format(Locale.US, "%.0fs left  •  best lvl %d", ceilPos(mTimeLeft), bests[MAZE])
            else -> String.format(Locale.US, "%.1fs", gameT) to String.format(Locale.US, "best %.1fs", bests[BALANCE] / 10f)
        }
        txt.textAlign = Paint.Align.LEFT
        txt.color = if (game == MAZE && mTimeLeft < 6f && st == St.PLAY) red else Color.WHITE
        txt.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        txt.textSize = dp(32f)
        c.drawText(big, dp(22f), dp(84f), txt)
        txt.typeface = Typeface.DEFAULT
        txt.textSize = dp(12f)
        txt.color = 0xFF8A8A92.toInt()
        c.drawText(small, dp(24f), dp(104f), txt)

        if (st == St.PLAY && gameT < 3f) {
            val hint = when (game) {
                DODGE -> "Drag anywhere to move  •  dodge everything"
                MAZE -> "Tilt to roll  •  get back up into the camera"
                else -> "Tilt left / right  •  don't fall off"
            }
            txt.textAlign = Paint.Align.CENTER
            txt.color = Color.WHITE
            txt.alpha = ((3f - gameT).coerceIn(0f, 1f) * 255).toInt()
            txt.textSize = dp(15f)
            c.drawText(hint, w / 2f, h - dp(60f), txt)
            txt.alpha = 255
        }

        if (st == St.OVER) drawOver(c, w, h)
        drawCloseBtn(c)
    }

    private fun ceilPos(v: Float) = kotlin.math.ceil(max(0f, v))

    private fun drawCloseBtn(c: Canvas) {
        val w = width.toFloat()
        closeBtn.set(w - dp(64f), dp(52f), w - dp(20f), dp(96f))
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1E1E26.toInt()
        c.drawCircle(closeBtn.centerX(), closeBtn.centerY(), dp(20f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        val k = dp(6f)
        c.drawLine(closeBtn.centerX() - k, closeBtn.centerY() - k, closeBtn.centerX() + k, closeBtn.centerY() + k, paint)
        c.drawLine(closeBtn.centerX() - k, closeBtn.centerY() + k, closeBtn.centerX() + k, closeBtn.centerY() - k, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    private fun drawPlayerBody(c: Canvas, x: Float, y: Float, r: Float, lx: Float, ly: Float, dead: Boolean, wide: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF202028.toInt()
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(dp(1.2f), r * 0.1f)
        paint.color = Color.WHITE
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL
        drawEyes(c, x, y, r, lx, ly, wide = wide, dead = dead)
    }

    private fun drawEyes(c: Canvas, cx: Float, cy: Float, size: Float, lx: Float, ly: Float, wide: Boolean, dead: Boolean = false) {
        val off = size * 0.4f
        val er = size * (if (wide) 0.34f else 0.28f)
        for (sx in floatArrayOf(-1f, 1f)) {
            val ex = cx + sx * off
            if (dead) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = er * 0.45f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.WHITE
                c.drawLine(ex - er * 0.7f, cy - er * 0.7f, ex + er * 0.7f, cy + er * 0.7f, paint)
                c.drawLine(ex - er * 0.7f, cy + er * 0.7f, ex + er * 0.7f, cy - er * 0.7f, paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                c.drawCircle(ex, cy, er, paint)
                paint.color = Color.BLACK
                c.drawCircle(ex + lx * er * 0.45f, cy + ly * er * 0.45f, er * (if (wide) 0.38f else 0.5f), paint)
            }
        }
    }

    private fun drawOver(c: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xCC000000.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        val cw = min(w - dp(48f), dp(320f))
        r2.set(w / 2f - cw / 2f, h / 2f - dp(130f), w / 2f + cw / 2f, h / 2f + dp(130f))
        paint.color = 0xFF16161C.toInt()
        c.drawRoundRect(r2, dp(24f), dp(24f), paint)

        val title = when {
            newBest -> "New best! 🎉"
            game == MAZE -> "Time's up!"
            game == BALANCE -> "Whoops!"
            else -> "Splat!"
        }
        val big = when (game) {
            DODGE -> "$score"
            MAZE -> "${mLevel - 1}"
            else -> String.format(Locale.US, "%.1fs", gameT)
        }
        val sub = when (game) {
            DODGE -> String.format(Locale.US, "survived %.1fs  •  best %d", gameT, bests[DODGE])
            MAZE -> "levels cleared  •  best ${bests[MAZE]}"
            else -> String.format(Locale.US, "balanced  •  best %.1fs", bests[BALANCE] / 10f)
        }
        txt.textAlign = Paint.Align.CENTER
        txt.color = Color.WHITE
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.textSize = dp(22f)
        c.drawText(title, w / 2f, r2.top + dp(48f), txt)
        txt.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        txt.textSize = dp(46f)
        c.drawText(big, w / 2f, r2.top + dp(108f), txt)
        txt.typeface = Typeface.DEFAULT
        txt.textSize = dp(13f)
        txt.color = 0xFFA8A8B0.toInt()
        c.drawText(sub, w / 2f, r2.top + dp(134f), txt)

        val bw = (cw - dp(48f)) / 2f
        againBtn.set(r2.left + dp(16f), r2.bottom - dp(76f), r2.left + dp(16f) + bw, r2.bottom - dp(24f))
        menuBtn.set(againBtn.right + dp(16f), againBtn.top, againBtn.right + dp(16f) + bw, againBtn.bottom)
        paint.color = Color.WHITE
        c.drawRoundRect(againBtn, dp(16f), dp(16f), paint)
        paint.color = 0xFF2A2A33.toInt()
        c.drawRoundRect(menuBtn, dp(16f), dp(16f), paint)
        txt.textSize = dp(15f)
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.color = Color.BLACK
        c.drawText("Play again", againBtn.centerX(), againBtn.centerY() + dp(5f), txt)
        txt.color = Color.WHITE
        c.drawText("Games", menuBtn.centerX(), menuBtn.centerY() + dp(5f), txt)
    }

    // =====================================================================
    // touch
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        if (e.actionMasked == MotionEvent.ACTION_DOWN && st != St.CLOSE && hitClose(x, y)) {
            haptic()
            close()
            return true
        }
        when (st) {
            St.MENU -> if (e.actionMasked == MotionEvent.ACTION_UP) {
                for (i in 0 until 3) if (cards[i].contains(x, y)) {
                    haptic()
                    game = i
                    startPlay()
                    break
                }
            }
            St.PLAY -> if (game == DODGE) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastTouchX = x; lastTouchY = y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (x - lastTouchX) * 1.25f
                    val dy = (y - lastTouchY) * 1.25f
                    lastTouchX = x; lastTouchY = y
                    px = (px + dx).coerceIn(pr, width - pr)
                    py = (py + dy).coerceIn(dp(110f) + pr, height - pr)
                    pvx = pvx * 0.5f + dx / 0.016f * 0.5f
                    pvy = pvy * 0.5f + dy / 0.016f * 0.5f
                }
            }
            St.OVER -> if (e.actionMasked == MotionEvent.ACTION_UP) {
                when {
                    againBtn.contains(x, y) -> { haptic(); fillCx = 0f; startPlay() }
                    menuBtn.contains(x, y) -> { haptic(); openMenu() }
                }
            }
            else -> {}
        }
        return true
    }

    private fun hitClose(x: Float, y: Float): Boolean {
        if (closeBtn.isEmpty) return false
        r2.set(closeBtn)
        r2.inset(-dp(10f), -dp(10f))
        return r2.contains(x, y)
    }
}
