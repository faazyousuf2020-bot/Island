package com.faaz.island

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var a11yStatus: TextView
    private lateinit var notifStatus: TextView
    private val grey = 0xFFA8A8B0.toInt()
    private val accent = 0xFF6C5CE7.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E0E12.toInt()
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(48))
        }

        root.addView(text("Faaz Island", 30f, Color.WHITE, bold = true))
        root.addView(text("A fun, useful pill around your front camera.", 14f, grey))

        root.addView(header("Setup"))
        a11yStatus = text("", 15f, Color.WHITE)
        root.addView(a11yStatus)
        root.addView(button("1 · Turn on Faaz Island in Accessibility") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        notifStatus = text("", 15f, Color.WHITE)
        root.addView(notifStatus)
        root.addView(button("2 · Allow notification access (popups + music)") {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(text(
            "Option greyed out or says \"Restricted setting\"? Tap below → ⋮ (top-right) → " +
                    "Allow restricted settings, then come back and try again.", 13f, grey))
        root.addView(button("Open app info") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(header("Switches"))
        root.addView(switch("Show island", Prefs.ON, true))
        root.addView(switch("Notification popups", Prefs.NOTIF, true))
        root.addView(switch("Charging animation", Prefs.CHARGE, true))
        root.addView(switch("Spinning ring when camera is in use", Prefs.CAMRING, true))
        root.addView(switch("Googly eyes when idle 👀", Prefs.EYES, false))

        root.addView(header("Fit it to your camera"))
        root.addView(slider("Width", Prefs.W, 50, 240, 96))
        root.addView(slider("Height", Prefs.H, 18, 60, 30))
        root.addView(text("The pill snaps to your camera automatically. Use these only for small tweaks.", 13f, grey))
        val dyBar = slider("Fine-tune up / down", Prefs.DY, 0, 60, 30)
        val dxBar = slider("Fine-tune left / right", Prefs.DX, 0, 100, 50)
        root.addView(dyBar)
        root.addView(dxBar)
        root.addView(button("Reset size & position") {
            prefs.edit().remove(Prefs.W).remove(Prefs.H).remove(Prefs.DY).remove(Prefs.DX).apply()
            recreate()
        })
        root.addView(button("Test a popup") {
            val sink = IslandBus.onNotification
            if (sink == null) {
                Toast.makeText(this, "Do step 1 first", Toast.LENGTH_SHORT).show()
            } else {
                val icon = try {
                    packageManager.getApplicationIcon(packageName).toBmp(dp(48))
                } catch (e: Exception) { null }
                sink(Alert(Alert.Kind.NOTIFICATION, "Hello Faaz 👋",
                    "This is how your notifications will pop up", icon = icon, pkg = packageName))
            }
        })

        root.addView(header("How to use"))
        root.addView(text(
            "• Tap the pill → big view (clock, battery, torch, timers)\n" +
                    "• Playing music → album art + dancing bars; tap for controls\n" +
                    "• Swipe sideways in big view → music ↔ controls\n" +
                    "• Swipe down on pill → open  •  Swipe up → close\n" +
                    "• Any app using the camera → green ring spins around it\n" +
                    "• Double-tap pill → eyes on/off\n" +
                    "• Long-press pill → this screen\n" +
                    "• Tap a notification popup → opens it\n" +
                    "• Hides itself in landscape", 14f, Color.WHITE).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E0E12.toInt())
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        a11yStatus.text = if (a11yOn()) "✅ Island service is on" else "❌ Island service is off"
        notifStatus.text = if (notifAccessOn()) "✅ Notification access allowed" else "❌ Notification access not allowed"
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun notifAccessOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains("$packageName/")
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- tiny UI helpers ----------------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun header(s: String) = text(s.uppercase(), 12f, accent, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(28), 0, dp(8))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = GradientDrawable().apply {
            setColor(0xFF22222A.toInt())
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, key: String, def: Boolean) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = prefs.getBoolean(key, def)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    private fun slider(label: String, key: String, min: Int, max: Int, def: Int): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val title = text("", 14f, Color.WHITE)
        val bar = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = prefs.getInt(key, def)
        }
        fun show(v: Int) { title.text = "$label  ·  $v" }
        show(bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                show(v)
                if (fromUser) prefs.edit().putInt(key, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(title)
        box.addView(bar)
        box.gravity = Gravity.START
        return box
    }
}
