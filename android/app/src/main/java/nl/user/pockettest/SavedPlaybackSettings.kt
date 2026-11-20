package nl.user.pockettest

import android.content.Context

object SavedPlaybackSettings {
    fun load(context: Context): PlaybackSettings {
        val saved = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        return PlaybackSettings(saved.getInt("speed_percent", 100), saved.getInt("pitch_semitones", 0))
    }

    fun save(context: Context, value: PlaybackSettings) {
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit()
            .putInt("speed_percent", value.speedPercent).putInt("pitch_semitones", value.pitchSemitones).apply()
        TestState.playbackSettings = value
    }

    /** Int8 model weights for slower phones. Each phone keeps its own choice. */
    fun loadInt8(context: Context): Boolean =
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).getBoolean("int8", false)

    fun saveInt8(context: Context, enabled: Boolean) {
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putBoolean("int8", enabled).apply()
    }

    /** Sleep timer minutes that each new Play starts. 0 means off. */
    fun loadTimerMinutes(context: Context): Long =
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).getLong("timer_minutes", 0L).coerceIn(0L, 180L)

    fun saveTimerMinutes(context: Context, minutes: Long) {
        require(minutes in 0..180) { "Invalid sleep timer minutes" }
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putLong("timer_minutes", minutes).apply()
    }
}
