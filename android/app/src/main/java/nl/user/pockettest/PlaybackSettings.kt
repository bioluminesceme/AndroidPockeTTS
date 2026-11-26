package nl.user.pockettest

import kotlin.math.pow

data class PlaybackSettings(val speedPercent: Int = 100, val pitchSemitones: Int = 0) {
    init {
        require(speedPercent in 75..150 && speedPercent % 5 == 0) { "Invalid reading speed" }
        require(pitchSemitones in -4..4) { "Invalid voice pitch" }
    }
    val speed: Float get() = speedPercent / 100f
    val pitch: Float get() = 2.0.pow(pitchSemitones / 12.0).toFloat()

    companion object {
        const val MIN_SPEED = 0.75f
    }
}

/** Voice variation is the model temperature in tenths. Higher values vary rhythm and melody more. */
object VoiceVariation {
    const val DEFAULT_TENTHS = 5
    val TENTHS = 2..8

    fun temperature(tenths: Int): Double {
        require(tenths in TENTHS) { "Invalid voice variation" }
        return tenths / 10.0
    }
}
