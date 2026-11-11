package app.screenrec

import android.content.Context

/** Simple wrapper around SharedPreferences for the recording options. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Shorter side of the video in pixels. 0 = native screen resolution. */
    var resolution: Int
        get() = sp.getInt("resolution", 1080)
        set(value) = sp.edit().putInt("resolution", value).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(value) = sp.edit().putInt("fps", value).apply()

    /** 0 = low, 1 = medium, 2 = high */
    var quality: Int
        get() = sp.getInt("quality", 1)
        set(value) = sp.edit().putInt("quality", value).apply()

    /** Seconds to wait before recording starts (0, 3 or 5). */
    var countdown: Int
        get() = sp.getInt("countdown", 3)
        set(value) = sp.edit().putInt("countdown", value).apply()

    var mic: Boolean
        get() = sp.getBoolean("mic", false)
        set(value) = sp.edit().putBoolean("mic", value).apply()

    /** Whether we've already asked for notification permission once. */
    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(value) = sp.edit().putBoolean("asked_notifications", value).apply()

    /** Bits per pixel per frame used to work out the video bitrate. */
    fun bitsPerPixel(): Double = when (quality) {
        0 -> 0.05
        2 -> 0.16
        else -> 0.09
    }
}
