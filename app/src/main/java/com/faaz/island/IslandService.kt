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
import android.graphics.PixelFormat
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
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) {}
        controller?.unregisterCallback(mcCallback)
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
        view.baseW = prefs.getInt(Prefs.W, 96) * d
        view.baseH = prefs.getInt(Prefs.H, 30) * d
        lp.y = (prefs.getInt(Prefs.Y, 6) * d).toInt()
        lp.x = ((prefs.getInt(Prefs.X, 50) - 50) * d).toInt()
        view.eyes = prefs.getBoolean(Prefs.EYES, false)
        notifOn = prefs.getBoolean(Prefs.NOTIF, true)
        chargeOn = prefs.getBoolean(Prefs.CHARGE, true)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        view.visibility = if (prefs.getBoolean(Prefs.ON, true) && !landscape) View.VISIBLE else View.GONE
        if (update && view.isAttachedToWindow) {
            try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
        }
        view.relayout()
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
            view.paused = i.action == Intent.ACTION_SCREEN_OFF
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

    private fun setupTorch() {
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
        view.refresh()
    }
}
