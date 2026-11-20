package nl.user.pockettest

/** Check sample order before a block enters the playback queue. */
class AudioOrder {
    var nextSample = 0L
        private set

    fun accept(firstSample: Long, sampleCount: Int) {
        require(sampleCount in 1..4096) { "Invalid callback size: $sampleCount" }
        require(firstSample == nextSample) { "Audio order error: expected $nextSample, received $firstSample" }
        nextSample = Math.addExact(nextSample, sampleCount.toLong())
    }
}

/** Convert the unsigned 32-bit AudioTrack position into a continuous position. */
class PlayedPosition {
    private var previous = 0L
    private var wraps = 0L
    fun update(raw: Int): Long {
        val value = raw.toLong() and 0xffffffffL
        if (value < previous) wraps += 1L shl 32
        previous = value
        return wraps + value
    }
}

/** Classify the observation. AudioTrack does not supply the event time. */
fun underrunObservation(cancelled: Boolean, generationFinished: Boolean,
                        queueEmpty: Boolean, blockPending: Boolean,
                        played: Long, written: Long): String = when {
    cancelled -> "cancelled"
    generationFinished && queueEmpty && !blockPending && written > 0 && played >= written ->
        "terminal_drain_observed"
    else -> "before_completion_observed"
}
