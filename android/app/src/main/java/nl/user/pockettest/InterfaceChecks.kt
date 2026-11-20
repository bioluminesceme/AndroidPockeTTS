package nl.user.pockettest

object InterfaceChecks {
    fun run(): String {
        val caller = Thread.currentThread().id
        val order = AudioOrder()
        check(PocketNative.probe(AudioSink { samples, first ->
            check(Thread.currentThread().id != caller) { "Callback used the caller thread" }
            order.accept(first, samples.size)
            0
        }) == 0)
        check(order.nextSample == 4113L)
        var calls = 0
        check(PocketNative.probe(AudioSink { _, _ -> calls++; 1 }) == 1)
        check(calls == 1)
        check(PocketNative.probe(AudioSink { _, _ -> 7 }) == 7)
        val expected = IllegalArgumentException("Callback exception test")
        var caught = false
        try { PocketNative.probe(AudioSink { _, _ -> throw expected }) }
        catch (error: IllegalArgumentException) { check(error === expected); caught = true }
        check(caught)
        var invalid = false
        try { PocketNative.stop(0) }
        catch (error: IllegalStateException) { check(error.message.orEmpty().contains("invalid handle")); invalid = true }
        check(invalid)
        return "PASS: JNI callback order, callback thread, cancellation, error status, exception transfer, and invalid handle"
    }
}
