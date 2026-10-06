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
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Full-screen "Pull & Dodge" game.
 * Pull the island's eyes down → let go → the blob drops, bounces and splats over the
 * whole screen → drag anywhere to move the blob and dodge everything.
 */
@SuppressLint("ViewConstructor")
class DodgeView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun onDodgeClosed()
        fun onDodgeBest(best: Int)
        fun setDodgeTouchable(touchable: Boolean)
    }

    private enum class St { PULL, SNAPBACK, DROP, FILL, PLAY, DEAD, OVER, CLOSE }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var best = 0
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
    private var lookX = 0f
    private var lookY = 0f

    // ---------------- game ----------------
    private class Hz(
        val type: Int, var x: Float, var y: Float, var vx: Float, var vy: Float,
        val r: Float, val horiz: Boolean = false, val life: Float = 99f
    ) {
        var age = 0f
        var dead = false
    }

    private val hz = ArrayList<Hz>()
    private var px = 0f
    private var py = 0f
    private var pvx = 0f
    private var pvy = 0f
    private val pr get() = dp(20f)
    private var gameT = 0f
    private var bonus = 0
    private var score = 0
    private var newBest = false
    private var deadAt = 0L
    private var lastFrame = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var nextMeteor = 0f
    private var nextLaser = 0f
    private var nextBouncer = 0f
    private var nextChaser = 0f
    private var nextStar = 0f
    private var popups = ArrayList<Triple<Float, Float, Long>>() // +50 text

    private val stars = Array(46) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }

    // ---------------- paints ----------------
    private val bg = 0xFF050507.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val dash = DashPathEffect(floatArrayOf(dp(10f), dp(8f)), 0f)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val r2 = RectF()
    private val againBtn = RectF()
    private val exitBtn = RectF()
    private val closeBtn = RectF()

    private val orange = 0xFFFFA94D.toInt()
    private val red = 0xFFFF4D5E.toInt()
    private val cyan = 0xFF4DD9FF.toInt()
    private val pink = 0xFFF783AC.toInt()
    private val gold = 0xFFFFD54F.toInt()
    private val green = 0xFF7CF29C.toInt()

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
        phaseStart = now()
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun startPlay() {
        st = St.PLAY
        hz.clear(); popups.clear()
        px = width / 2f; py = height * 0.68f
        if (fillCx > 0f) { px = fillCx; py = fillCy.coerceAtMost(height * 0.8f) }
        pvx = 0f; pvy = 0f
        gameT = 0f; bonus = 0; score = 0; newBest = false
        nextMeteor = 0.8f; nextLaser = 4f; nextBouncer = 9f; nextChaser = 20f; nextStar = 2.5f
        lastFrame = now()
    }

    fun close() {
        if (st == St.CLOSE || closed) return
        if (st == St.DROP) { px = bx; py = by }
        if (st == St.FILL) { px = fillCx; py = fillCy }
        host.setDodgeTouchable(false)
        st = St.CLOSE
        phaseStart = now()
        invalidate()
    }

    /** Remove immediately (screen off etc). */
    fun kill() {
        if (closed) return
        closed = true
        host.onDodgeClosed()
    }

    private fun finish() {
        if (closed) return
        closed = true
        post { host.onDodgeClosed() }
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
            St.PLAY, St.DEAD, St.OVER -> {
                if (st == St.PLAY) stepGame(dt)
                if (st == St.DEAD && now - deadAt > 750L) st = St.OVER
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

        val ready = live && dist >= launchDist
        if (ready) {
            val pulse = 0.5f + 0.5f * sin(now() / 120f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = green
            paint.alpha = (140 + 115 * pulse).toInt()
            c.drawCircle(x, y0, R + dp(5f) + pulse * dp(3f), paint)
            paint.alpha = 255
        }
        // eyes strain toward the camera while you pull
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

    private fun squashAmount(now: Long): Float {
        val t = (now - squashAt) / 160f
        return if (t in 0f..1f) (1f - t) else 0f
    }

    private fun drawDrop(c: Canvas, now: Long) {
        val s = squashAmount(now) * 0.35f
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
        // white shock ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f) * (1f - f) + dp(1f)
        paint.color = Color.WHITE
        paint.alpha = ((1f - f) * 200).toInt()
        c.drawCircle(fillCx, fillCy, r, paint)
        paint.alpha = 255
        // eyes pop big, then settle
        val pop = 1f + 1.2f * sin(f * Math.PI.toFloat())
        drawPlayerBody(c, fillCx, fillCy, pr * pop, 0f, 0f, false)
        if (f >= 1f) startPlay()
    }

    // ---------------- close ----------------

    private fun drawClose(c: Canvas, now: Long): Boolean {
        val f = ((now - phaseStart) / 430f).coerceIn(0f, 1f)
        val e = f * f
        val tx = pill.centerX()
        val ty = pill.centerY()
        val cx = px + (tx - px) * e
        val cy = py + (ty - py) * e
        val r = maxR() + (pill.height() / 2f - maxR()) * e
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawCircle(cx, cy, r, paint)
        drawEyes(c, cx, cy, blobR * (1f - 0.5f * e), 0f, -1f, false)
        return f < 1f
    }

    // =====================================================================
    // the game
    // =====================================================================

    private fun difficulty() = 1f + gameT / 25f

    private fun stepGame(dt: Float) {
        gameT += dt
        score = (gameT * 10).toInt() + bonus
        val k = difficulty()
        val w = width.toFloat()
        val h = height.toFloat()

        // spawns
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

        // move + collide
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
        // ease the eye-look toward movement
        lookX += ((pvx / dp(600f)).coerceIn(-1f, 1f) - lookX) * min(1f, dt * 10f)
        lookY += ((pvy / dp(600f)).coerceIn(-1f, 1f) - lookY) * min(1f, dt * 10f)
        pvx *= 0.85f; pvy *= 0.85f
    }

    private fun near(o: Hz, extra: Float): Boolean {
        val dx = px - o.x; val dy = py - o.y
        val rr = o.r + extra
        return dx * dx + dy * dy < rr * rr
    }

    private fun die() {
        st = St.DEAD
        deadAt = now()
        haptic(HapticFeedbackConstants.LONG_PRESS)
        if (score > best) {
            best = score
            newBest = true
            host.onDodgeBest(best)
        }
    }

    // ---------------- drawing ----------------

    private fun drawGame(c: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(bg)

        c.save()
        val shake = if (st == St.DEAD) (1f - ((now - deadAt) / 320f)).coerceIn(0f, 1f) else 0f
        if (shake > 0f) c.translate((Random.nextFloat() - 0.5f) * dp(16f) * shake, (Random.nextFloat() - 0.5f) * dp(16f) * shake)

        // twinkling backdrop
        paint.style = Paint.Style.FILL
        for (s in stars) {
            paint.color = Color.WHITE
            paint.alpha = (30 + 60 * (0.5f + 0.5f * sin(now / 600f + s[2] * 20f))).toInt()
            c.drawCircle(s[0] * w, (s[1] * h + gameT * dp(12f) * (0.5f + s[2])) % h, dp(1.2f) + s[2] * dp(1f), paint)
        }
        paint.alpha = 255

        for (o in hz) drawHazard(c, o, now)
        drawPlayerBody(c, px, py, pr, lookX, lookY, st != St.PLAY)

        // "+50" popups
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
        c.restore()

        // HUD (kept clear of the camera in the middle)
        txt.textAlign = Paint.Align.LEFT
        txt.color = Color.WHITE
        txt.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        txt.textSize = dp(34f)
        c.drawText("$score", dp(22f), dp(84f), txt)
        txt.typeface = Typeface.DEFAULT
        txt.textSize = dp(12f)
        txt.color = 0xFF8A8A92.toInt()
        c.drawText(String.format(Locale.US, "%.1fs  •  best %d", gameT, best), dp(24f), dp(104f), txt)

        // close button
        closeBtn.set(w - dp(64f), dp(52f), w - dp(20f), dp(96f))
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

        if (st == St.PLAY && gameT < 3f) {
            txt.textAlign = Paint.Align.CENTER
            txt.color = Color.WHITE
            txt.alpha = (((3f - gameT) / 1f).coerceIn(0f, 1f) * 255).toInt()
            txt.textSize = dp(15f)
            c.drawText("Drag anywhere to move  •  dodge everything", w / 2f, h - dp(70f), txt)
            txt.alpha = 255
        }

        if (st == St.OVER) drawOver(c, w, h)
    }

    private fun drawHazard(c: Canvas, o: Hz, now: Long) {
        paint.style = Paint.Style.FILL
        when (o.type) {
            0 -> { // meteor with trail
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
            1 -> { // laser: warn, then fire
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
                    paint.alpha = 255
                    val ct = t * 0.3f
                    if (o.horiz) c.drawRect(0f, o.y - ct, w, o.y + ct, paint) else c.drawRect(o.x - ct, 0f, o.x + ct, h, paint)
                }
                paint.alpha = 255
                paint.style = Paint.Style.FILL
            }
            2 -> { // bouncer
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
            3 -> { // homing chaser
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
            4 -> { // star
                val rot = o.age * 2f
                path.reset()
                for (i in 0 until 10) {
                    val a = rot + i * 0.6283f - 1.5708f
                    val rr = if (i % 2 == 0) o.r else o.r * 0.45f
                    val x = o.x + cos(a) * rr
                    val y = o.y + sin(a) * rr
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                paint.color = gold
                c.drawPath(path, paint)
            }
        }
    }

    private fun drawPlayerBody(c: Canvas, x: Float, y: Float, r: Float, lx: Float, ly: Float, dead: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF202028.toInt()
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.WHITE
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL
        drawEyes(c, x, y, r, lx, ly, wide = false, dead = dead)
    }

    /** Two white eyes centred on (cx, cy), sized for a blob of radius [size]. */
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

        txt.textAlign = Paint.Align.CENTER
        txt.color = Color.WHITE
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.textSize = dp(22f)
        c.drawText(if (newBest) "New best! 🎉" else "Splat!", w / 2f, r2.top + dp(48f), txt)
        txt.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        txt.textSize = dp(46f)
        c.drawText("$score", w / 2f, r2.top + dp(108f), txt)
        txt.typeface = Typeface.DEFAULT
        txt.textSize = dp(13f)
        txt.color = 0xFFA8A8B0.toInt()
        c.drawText(String.format(Locale.US, "survived %.1fs  •  best %d", gameT, best), w / 2f, r2.top + dp(134f), txt)

        val bw = (cw - dp(48f)) / 2f
        againBtn.set(r2.left + dp(16f), r2.bottom - dp(76f), r2.left + dp(16f) + bw, r2.bottom - dp(24f))
        exitBtn.set(againBtn.right + dp(16f), againBtn.top, againBtn.right + dp(16f) + bw, againBtn.bottom)
        paint.color = Color.WHITE
        c.drawRoundRect(againBtn, dp(16f), dp(16f), paint)
        paint.color = 0xFF2A2A33.toInt()
        c.drawRoundRect(exitBtn, dp(16f), dp(16f), paint)
        txt.textSize = dp(15f)
        txt.typeface = Typeface.DEFAULT_BOLD
        txt.color = Color.BLACK
        c.drawText("Play again", againBtn.centerX(), againBtn.centerY() + dp(5f), txt)
        txt.color = Color.WHITE
        c.drawText("Exit", exitBtn.centerX(), exitBtn.centerY() + dp(5f), txt)
    }

    // =====================================================================
    // touch
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (st) {
            St.PLAY -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hitClose(x, y)) { close(); return true }
                    lastTouchX = x; lastTouchY = y
                }
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
                    exitBtn.contains(x, y) || hitClose(x, y) -> { haptic(); close() }
                }
            }
            St.DEAD, St.DROP, St.FILL -> if (e.actionMasked == MotionEvent.ACTION_DOWN && hitClose(x, y)) close()
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
