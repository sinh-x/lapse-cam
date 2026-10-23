package com.sinh.timelapse

import android.content.SharedPreferences

/** The existing new-session preferences, not session metadata or a settings migration. */
internal class CaptureDefaults(private val preferences: SharedPreferences) {
    val adaptive: Boolean get() = preferences.getBoolean("adaptive", true)
    val maxSeconds: Int get() = preferences.getInt("maxSeconds", 30)
    val interval: Int get() = preferences.getInt("interval", 5)
    val fps: Int get() = preferences.getInt("fps", 30)
    val split: SessionSplit get() = runCatching {
        SessionSplit.parse(preferences.getString("split", SessionSplit.DEFAULT.storedValue)!!)
    }.getOrDefault(SessionSplit.DEFAULT)

    fun appearance(): TimestampAppearance = runCatching {
        TimestampAppearance(StampFamily.valueOf(preferences.getString("family", "MONOSPACE")!!),
            preferences.getBoolean("bold", true), preferences.getInt("size", 44),
            StampPosition.valueOf(preferences.getString("position", "BOTTOM_LEFT")!!), preferences.getBoolean("date", false))
    }.getOrDefault(TimestampAppearance())

    fun rememberCapture(configuration: SamplingConfiguration, interval: Int, split: SessionSplit) {
        preferences.edit().putBoolean("adaptive", configuration is SamplingConfiguration.Adaptive)
            .putInt("maxSeconds", (configuration as? SamplingConfiguration.Adaptive)?.maxSeconds ?: maxSeconds)
            .putInt("fps", configuration.fps).putInt("interval", interval)
            .putString("split", split.storedValue).apply()
    }

    fun rememberAppearance(appearance: TimestampAppearance) {
        preferences.edit().putString("family", appearance.family.name).putBoolean("bold", appearance.bold)
            .putInt("size", appearance.size).putString("position", appearance.position.name)
            .putBoolean("date", appearance.date).apply()
    }
}
