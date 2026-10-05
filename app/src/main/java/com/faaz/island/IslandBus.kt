package com.faaz.island

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Simple in-app messenger between the notification listener, the island and settings. */
object IslandBus {
    var onNotification: ((Alert) -> Unit)? = null
    var onListenerReady: (() -> Unit)? = null
}

object Prefs {
    const val NAME = "island"
    const val ON = "on"
    const val NOTIF = "notif"
    const val CHARGE = "charge"
    const val EYES = "eyes"
    const val W = "w"
    const val H = "h"
    const val Y = "y"
    const val X = "x"
}

fun Drawable.toBmp(size: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    setBounds(0, 0, size, size)
    draw(c)
    return b
}
