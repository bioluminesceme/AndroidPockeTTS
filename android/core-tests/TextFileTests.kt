package nl.user.pockettest

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

fun runTextFileTests() {
    fun read(value: String): String {
        val sentences = mutableListOf<String>()
        TextFile.sentences(value.byteInputStream(Charsets.UTF_8)) { sentences.add(it) }
        return sentences.joinToString("\n")
    }
    fun reject(bytes: ByteArray) {
        var rejected = false
        try { TextFile.sentences(ByteArrayInputStream(bytes)) {} }
        catch (_: IllegalArgumentException) { rejected = true }
        catch (_: java.nio.charset.CharacterCodingException) { rejected = true }
        check(rejected) { "Invalid TXT input was accepted" }
    }
    check(read("\uFEFFHello world.\r\nNext sentence!") == "Hello world.\nNext sentence!")
    check(read("First sentence. Second sentence.") == "First sentence.\nSecond sentence.")
    check(read("A wrapped\r\nline with café.") == "A wrapped line with café.")
    check(read("a".repeat(TextFile.SENTENCE_LIMIT)).length == TextFile.SENTENCE_LIMIT)
    reject("a".repeat(TextFile.SENTENCE_LIMIT + 1).toByteArray())
    reject(byteArrayOf(0xC3.toByte(), 0x28))
    reject(" \r\n\t".toByteArray())
    reject("binary\u0000data".toByteArray())
    val directory = Files.createTempDirectory("pocket-text-test").toFile()
    try {
        val large = "A complete sentence.\n".repeat(100000)
        val name = PlaybackText.write(directory, large)
        check(name.length < 100)
        PlaybackText.open(directory, name).use { reader ->
            var count = 0
            reader.forEachLine { check(it == "A complete sentence."); count++ }
            check(count == 100000)
        }
        var invalidPathRejected = false
        try { PlaybackText.open(directory, "../playback-input.txt") }
        catch (_: IllegalArgumentException) { invalidPathRejected = true }
        check(invalidPathRejected)
        var oversizedRejected = false
        try { PlaybackText.write(directory, "a".repeat(TextFile.SENTENCE_LIMIT + 1)) }
        catch (_: IllegalArgumentException) { oversizedRejected = true }
        check(oversizedRejected)
        PlaybackText.open(directory, name).use { check(it.readLine() == "A complete sentence.") }
        val boundaryText = "x".repeat(4090) + " café. Second sentence! Last sentence."
        check(read(boundaryText).filterNot { it.isWhitespace() } == boundaryText.filterNot { it.isWhitespace() })
        var consumed = 0L
        val generated = object : java.io.InputStream() {
            val block = "A complete sentence. ".toByteArray()
            override fun read(): Int {
                if (consumed >= 4_000_000) return -1
                return block[(consumed++ % block.size).toInt()].toInt()
            }
        }
        var firstCallbackBytes = 0L
        var sentenceCount = 0
        TextFile.sentences(generated) {
            if (sentenceCount++ == 0) firstCallbackBytes = consumed
            check(it.length <= TextFile.SENTENCE_LIMIT)
        }
        check(firstCallbackBytes < 20000)
        check(sentenceCount > 100000)
        val navigation = PlaybackText.write(directory, "First sentence. Second sentence. Third sentence.")
        val beginning = PlaybackText.position(directory, navigation, 0.0)
        check(beginning.line == 0 && beginning.preview.startsWith("First sentence."))
        val middle = PlaybackText.position(directory, navigation, 50.0)
        check(middle.line == 1 && middle.preview.startsWith("Second sentence."))
        PlaybackText.openAt(directory, navigation, middle.line).use { check(it.readLine() == "Second sentence.") }
        val end = PlaybackText.position(directory, navigation, 100.0)
        check(end.line == 3 && end.preview.isEmpty())
        PlaybackText.openAt(directory, navigation, end.line).use { check(it.readLine() == null) }
        for (invalid in listOf(-1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            var failed = false
            try { PlaybackText.position(directory, navigation, invalid) }
            catch (_: IllegalArgumentException) { failed = true }
            check(failed)
        }
        println("PASS: Percentage navigation at start, middle, end, sentence boundaries, and invalid positions")
    } finally {
        directory.listFiles()?.forEach { check(it.delete()) }
        check(directory.delete())
    }
    println("PASS: Chunked reading, large files, bounded preview, path validation, and failed-import recovery")
    println("PASS: TXT UTF-8 decoding, BOM, sentence separation, sentence limit, and invalid input")
}
