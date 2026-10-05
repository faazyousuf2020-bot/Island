package com.faaz.island

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt
import android.graphics.Rect
import android.util.DisplayMetrics
import kotlin.math.max
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class IslandService : AccessibilityService(), IslandView.Host {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var view: IslandView
    private lateinit var prefs: SharedPreferences
    private var ready = false
    private var notifOn = true
    private var chargeOn = true
    private var lastPlugged: Boolean? = null

    // ---------------- lifecycle ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view = IslandView(this, this)
        applyPrefs(false)
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            return
        }
        ready = true

        register(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        register(screenRx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        setupTorch()

        IslandBus.onNotification = { a ->
            handler.post { if (ready && notifOn && prefs.getBoolean(Prefs.ON, true)) view.showAlert(a) }
        }
        IslandBus.onListenerReady = { handler.post { setupMedia() } }
        setupMedia()
        updateSensors()
        view.refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ready) applyPrefs(true)
    }

    override fun onDestroy() {
        ready = false
        IslandBus.onNotification = null
        IslandBus.onListenerReady = null
        try { unregisterReceiver(batteryRx) } catch (e: Exception) {}
        try { unregisterReceiver(screenRx) } catch (e: Exception) {}
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (e: Exception) {}
        try { cam.unregisterTorchCallback(torchCb) } catch (e: Exception) {}
        try { cam.unregisterAvailabilityCallback(camAvail) } catch (e: Exception) {}
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) {}
        controller?.unregisterCallback(mcCallback)
        try { sm.unregisterListener(accel) } catch (e: Exception) {}
        releaseViz()
        try { wm.removeView(view) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun register(rx: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(rx, f)
    }

    // ---------------- settings ----------------

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (ready) applyPrefs(true)
    }

    private fun applyPrefs(update: Boolean) {
        val d = resources.displayMetrics.density
        val dx = (prefs.getInt(Prefs.DX, 50) - 50) * d
        val dy = (prefs.getInt(Prefs.DY, 30) - 30) * d
        val bw = prefs.getInt(Prefs.W, 96) * d
        val bh = prefs.getInt(Prefs.H, 30) * d

        // Find the real camera hole and center the pill on it
        val screenW = realScreenWidth()
        val cut = cameraCutout()
        val camCx: Float
        val camCy: Float
        val camR: Float
        if (cut != null) {
            camCx = cut.exactCenterX()
            camCy = cut.exactCenterY()
            camR = max(cut.width(), cut.height()) / 2f
        } else {
            camCx = screenW / 2f
            camCy = 6 * d + bh / 2f
            camR = 7 * d
        }
        lp.x = (camCx - screenW / 2f + dx).toInt()
        lp.y = (camCy - bh / 2f + dy).toInt()
        view.baseW = bw
        view.baseH = bh
        // camera position inside the island window (window is centered on camCx + dx)
        view.camDx = -dx
        view.camY = camCy - lp.y
        view.camR = camR

        view.eyes = prefs.getBoolean(Prefs.EYES, false)
        camRingOn = prefs.getBoolean(Prefs.CAMRING, true)
        view.glowOn = prefs.getBoolean(Prefs.GLOW, true)
        view.camInUse = camRingOn && busyCams.isNotEmpty()
        notifOn = prefs.getBoolean(Prefs.NOTIF, true)
        chargeOn = prefs.getBoolean(Prefs.CHARGE, true)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        view.visibility = if (prefs.getBoolean(Prefs.ON, true) && !landscape) View.VISIBLE else View.GONE
        if (update && view.isAttachedToWindow) {
            try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
        }
        view.relayout()
        updateSensors()
        updateViz()
    }

    // ---------------- IslandView.Host ----------------

    override fun resizeWindow(w: Int, h: Int) {
        if (!ready || !view.isAttachedToWindow) return
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
    }

    override fun onAction(action: Action, alert: Alert?) {
        when (action) {
            Action.TORCH -> toggleTorch()
            Action.TIMER_1 -> addTimer(60_000L)
            Action.TIMER_5 -> addTimer(300_000L)
            Action.TIMER_STOP -> { view.timerEnd = 0L; view.timerTotal = 0L }
            Action.EYES -> {
                prefs.edit().putBoolean(Prefs.EYES, !view.eyes).apply()
                view.collapse()
            }
            Action.PLAY_PAUSE -> controller?.let {
                if (it.playbackState?.state == PlaybackState.STATE_PLAYING) it.transportControls.pause()
                else it.transportControls.play()
            }
            Action.NEXT -> controller?.transportControls?.skipToNext()
            Action.PREV -> controller?.transportControls?.skipToPrevious()
            Action.SETTINGS -> {
                view.collapse()
                try {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {}
            }
            Action.OPEN_ALERT -> openAlert(alert)
        }
        view.invalidate()
    }

    override fun onTimerDone() {
        buzz()
        view.showAlert(Alert(Alert.Kind.TIMER_DONE, "Time's up!", "Your timer finished", durationMs = 6000L))
    }

    // ---------------- features ----------------

    private fun addTimer(ms: Long) {
        val now = System.currentTimeMillis()
        if (view.timerEnd > now) {
            view.timerEnd += ms
            view.timerTotal += ms
        } else {
            view.timerEnd = now + ms
            view.timerTotal = ms
        }
        view.refresh()
    }

    private fun openAlert(a: Alert?) {
        if (a == null) return
        try {
            val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            val pi = a.intent
            if (pi != null) {
                pi.send(this, 0, null, null, null, null, opts)
            } else {
                val launch = a.pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val key = a.key
            if (a.autoCancel && key != null) NotifListener.instance?.cancelNotification(key)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 450), -1))
        } catch (e: Exception) {
        }
    }

    // battery + charging animation
    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (scale > 0) level * 100 / scale else level
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            view.battery = pct
            view.charging = plugged
            val prev = lastPlugged
            lastPlugged = plugged
            if (prev == false && plugged && chargeOn && prefs.getBoolean(Prefs.ON, true)) {
                view.showAlert(Alert(Alert.Kind.CHARGING, "Charging", "$pct%", durationMs = 3500L))
            }
            view.invalidate()
        }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOff = i.action == Intent.ACTION_SCREEN_OFF
            if (!screenOff) view.lastMove = SystemClock.uptimeMillis()
            view.paused = screenOff
            updateSensors()
            updateViz()
        }
    }

    // torch
    private val cam by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private var torchId: String? = null
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                view.torchOn = enabled
                view.invalidate()
            }
        }
    }

    // camera-in-use ring
    private var camRingOn = true
    private val busyCams = HashSet<String>()
    private val camAvail = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            busyCams.remove(cameraId)
            updateCamRing()
        }

        override fun onCameraUnavailable(cameraId: String) {
            busyCams.add(cameraId)
            updateCamRing()
        }
    }

    private fun updateCamRing() {
        if (!ready) return
        view.camInUse = camRingOn && busyCams.isNotEmpty()
        view.refresh()
    }

    @Suppress("DEPRECATION")
    private fun cameraCutout(): Rect? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val dc = wm.defaultDisplay.cutout ?: return null
            val h = resources.displayMetrics.heightPixels
            dc.boundingRects.filter { it.top < h / 4 && !it.isEmpty }.minByOrNull { it.top }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun realScreenWidth(): Int = try {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        dm.widthPixels
    } catch (e: Exception) {
        resources.displayMetrics.widthPixels
    }

    private fun setupTorch() {
        try {
            cam.registerAvailabilityCallback(camAvail, handler)
        } catch (e: Exception) {
        }
        try {
            torchId = cam.cameraIdList.firstOrNull {
                cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cam.registerTorchCallback(torchCb, handler)
        } catch (e: Exception) {
        }
    }

    private fun toggleTorch() {
        val id = torchId ?: return
        try { cam.setTorchMode(id, !view.torchOn) } catch (e: Exception) {}
    }

    // music
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list)
    }

    private val mcCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMedia()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMedia()
        }

        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            updateMedia()
        }
    }

    private fun setupMedia() {
        if (!ready) return
        try {
            val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(this, NotifListener::class.java)
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
            m.addOnActiveSessionsChangedListener(sessionsListener, cn)
            msm = m
            pickController(m.getActiveSessions(cn))
        } catch (e: Exception) {
            // notification access not granted yet
        }
    }

    private fun pickController(list: List<MediaController>?) {
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(mcCallback)
            controller = pick
            pick?.registerCallback(mcCallback, handler)
        }
        updateMedia()
    }

    private fun updateMedia() {
        if (!ready) return
        val c = controller
        val md = c?.metadata
        view.media = if (c == null || md == null) null else {
            val st = c.playbackState?.state
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (title.isBlank()) null
            else MediaInfo(title, artist, art,
                st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING)
        }
        val art = view.media?.art
        if (art !== lastArt) {
            lastArt = art
            view.glowColor = vibrantColor(art)
        }
        updateViz()
        view.refresh()
    }

    // ---------------- pet mode: motion sensor ----------------

    private var screenOff = false
    private val sm by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private var accelOn = false
    private val lastG = FloatArray(3)
    private var spikeWindow = 0L
    private var spikeCount = 0

    private val accel = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]
            val y = e.values[1]
            val z = e.values[2]
            val now = SystemClock.uptimeMillis()
            val delta = abs(x - lastG[0]) + abs(y - lastG[1]) + abs(z - lastG[2])
            lastG[0] = x; lastG[1] = y; lastG[2] = z
            if (delta > 1.2f) view.lastMove = now
            // shake → dizzy
            val mag = sqrt(x * x + y * y + z * z)
            if (abs(mag - 9.81f) > 7f) {
                if (now - spikeWindow > 1000L) {
                    spikeWindow = now
                    spikeCount = 0
                }
                spikeCount++
                if (spikeCount >= 4) {
                    view.dizzyUntil = now + 2600L
                    spikeCount = 0
                }
            }
            // tilt → where the eyes look
            view.tiltX = (-x / 6f).coerceIn(-1f, 1f)
            view.tiltY = ((8f - y) / 5f).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateSensors() {
        if (!ready) return
        val want = view.eyes && !screenOff && view.visibility == View.VISIBLE
        if (want && !accelOn) {
            val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            accelOn = sm.registerListener(accel, s, SensorManager.SENSOR_DELAY_UI, handler)
        } else if (!want && accelOn) {
            sm.unregisterListener(accel)
            accelOn = false
        }
    }

    // ---------------- music glow ----------------

    private var lastArt: Bitmap? = null
    private var viz: Visualizer? = null
    private var avgLevel = 0f
    private val rms = Visualizer.MeasurementPeakRms()

    private fun updateViz() {
        if (!ready) return
        val want = view.glowOn && !screenOff && view.media?.playing == true &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (want && viz == null) {
            try {
                val v = Visualizer(0)
                v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
                v.setEnabled(true)
                viz = v
                view.levelSource = { readLevel() }
            } catch (e: Throwable) {
                viz = null
                view.levelSource = null
            }
        } else if (!want && viz != null) {
            releaseViz()
        }
    }

    private fun readLevel(): Float {
        val v = viz ?: return 0f
        return try {
            v.getMeasurementPeakRms(rms)
            // rms is in millibels: about -9600 (silent) to 0 (max)
            val level = ((rms.mRms + 4000f) / 3500f).coerceIn(0f, 1f)
            avgLevel = avgLevel * 0.95f + level * 0.05f
            ((level - avgLevel) * 3f + level * 0.35f).coerceIn(0f, 1f)
        } catch (e: Throwable) {
            0f
        }
    }

    private fun releaseViz() {
        view.levelSource = null
        try {
            viz?.setEnabled(false)
            viz?.release()
        } catch (e: Throwable) {
        }
        viz = null
    }

    /** Picks the most colourful pixel of the album art. */
    private fun vibrantColor(b: Bitmap?): Int {
        val fallback = 0xFF7CF29C.toInt()
        if (b == null) return fallback
        return try {
            val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val s = Bitmap.createScaledBitmap(src, 24, 24, true)
            val hsv = FloatArray(3)
            var best = fallback
            var bestScore = -1f
            for (x in 0 until 24) for (y in 0 until 24) {
                val p = s.getPixel(x, y)
                Color.colorToHSV(p, hsv)
                val score = hsv[1] * hsv[2] * (if (hsv[2] < 0.35f) 0.3f else 1f)
                if (score > bestScore) {
                    bestScore = score
                    best = p
                }
            }
            if (bestScore < 0.12f) return Color.WHITE
            Color.colorToHSV(best, hsv)
            hsv[1] = max(hsv[1], 0.6f)
            hsv[2] = max(hsv[2], 0.9f)
            Color.HSVToColor(hsv)
        } catch (e: Throwable) {
            fallback
        }
    }
}
