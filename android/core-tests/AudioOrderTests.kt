package nl.user.pockettest

private fun rejects(operation: () -> Unit) {
    var failed = false
    try { operation() } catch (_: IllegalArgumentException) { failed = true }
    check(failed) { "Invalid audio block was accepted" }
}

fun main(args: Array<String>) {
    try { runTests(args) }
    catch (error: Throwable) { error.printStackTrace(); kotlin.system.exitProcess(1) }
}

private fun runTests(args: Array<String>) {
    if (args.contentEquals(arrayOf("--documents-only"))) {
        runBookImportTests()
        return
    }
    require(args.isEmpty()) { "Unknown test arguments" }
    runActivityWorkScheduleTests()
    runPlaybackSettingsTests()
    runVoiceFavoritesTests()
    runVoiceTrashTests()
    runReaderStateTests()
    runPhraseSearchTests()
    runTextFileTests()
    runBookImportTests()
    check(underrunObservation(false, false, true, false, 4096, 4096) == "before_completion_observed")
    check(underrunObservation(false, true, false, false, 4096, 4096) == "before_completion_observed")
    check(underrunObservation(false, true, true, true, 4096, 4096) == "before_completion_observed")
    check(underrunObservation(false, true, true, false, 4095, 4096) == "before_completion_observed")
    check(underrunObservation(false, true, true, false, 4096, 4096) == "terminal_drain_observed")
    check(underrunObservation(false, true, true, false, 0, 0) == "before_completion_observed")
    check(underrunObservation(true, true, true, false, 4096, 4096) == "cancelled")
    check(underrunObservation(true, false, false, true, 2048, 4096) == "cancelled")
    println("PASS: Underrun observations before completion, terminal drain, and cancellation")
    runThermalReadingTests()
    val order = AudioOrder()
    rejects { order.accept(1, 4096) }
    rejects { order.accept(0, 0) }
    rejects { order.accept(0, 4097) }
    check(order.nextSample == 0L)
    order.accept(0, 4096)
    rejects { order.accept(0, 17) }
    rejects { order.accept(4097, 17) }
    check(order.nextSample == 4096L)
    order.accept(4096, 17)
    check(order.nextSample == 4113L)
    val position = PlayedPosition()
    check(position.update(0) == 0L)
    check(position.update(Int.MAX_VALUE) == 2147483647L)
    check(position.update(Int.MIN_VALUE) == 2147483648L)
    check(position.update(-1) == 4294967295L)
    check(position.update(0) == 4294967296L)
    check(position.update(100) == 4294967396L)
    check(position.update(100) == 4294967396L)
    println("PASS: Callback gaps, overlaps, sizes, contiguous audio, and playback-position wrap")
}
