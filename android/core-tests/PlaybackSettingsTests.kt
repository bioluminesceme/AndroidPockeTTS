package nl.user.pockettest

fun runPlaybackSettingsTests() {
    val normal = PlaybackSettings()
    check(normal.speed == 1f && normal.pitch == 1f)
    for (pitch in -4..4) for (speed in 75..150 step 5) {
        val settings = PlaybackSettings(speed, pitch)
        check(settings.copy(speedPercent = 100).pitch == settings.pitch)
        check(settings.copy(pitchSemitones = 0).speed == settings.speed)
        check(settings.pitch in 0.79f..1.26f)
    }
    for ((speed, pitch) in listOf(70 to 0, 155 to 0, 101 to 0, 100 to -5, 100 to 5)) {
        check(runCatching { PlaybackSettings(speed, pitch) }.exceptionOrNull() is IllegalArgumentException)
    }
    check(VoiceVariation.temperature(VoiceVariation.DEFAULT_TENTHS) == 0.5)
    check(VoiceVariation.temperature(2) == 0.2 && VoiceVariation.temperature(8) == 0.8)
    for (tenths in listOf(1, 9, 0, -5)) {
        check(runCatching { VoiceVariation.temperature(tenths) }.exceptionOrNull() is IllegalArgumentException)
    }
    val timer = SentenceTimer()
    timer.set(1, 0)
    // At 0.75x, 18,000 pending samples need one second at 24 kHz.
    check(timer.holdFuture(0, 0, 18000, (24000 * PlaybackSettings.MIN_SPEED).toInt()))
    check(!timer.holdFuture(0, 0, 18000, 24000))
    check(timer.observe(1000, 7))
    check(timer.targetLine == 7)
    println("PASS: Independent speed and pitch, limits, defaults, voice variation range, and slow-speed timer guard")
}
