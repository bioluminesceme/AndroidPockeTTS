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
