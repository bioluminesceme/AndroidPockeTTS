package nl.user.pockettest

fun interface AudioSink {
    fun onAudio(samples: FloatArray, firstSample: Long): Int
}

object PocketNative {
    init { System.loadLibrary("pocket_jni") }
    external fun create(config: ByteArray, settings: ByteArray): Long
    external fun selectVoice(handle: Long, speaker: ByteArray)
    external fun generate(handle: Long, text: ByteArray, sink: AudioSink): Int
    external fun stop(handle: Long)
    external fun close(handle: Long)
    external fun sampleRate(handle: Long): Int
    external fun probe(sink: AudioSink): Int
}
