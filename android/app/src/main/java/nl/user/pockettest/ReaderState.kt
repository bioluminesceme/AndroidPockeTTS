package nl.user.pockettest

import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque
import java.util.Properties
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class AudibleSentence(val file: String, val line: Int)
data class SentenceRange(val line: Int, val first: Long, var end: Long? = null)

object SentenceSelection {
    fun offsetInSentence(text: String, offset: Int): Int {
        val bounded = offset.coerceIn(0, text.length)
        val start = if (bounded == 0) 0 else text.lastIndexOf('\n', bounded - 1) + 1
        return bounded - start
    }
    fun lineAt(text: String, firstLine: Int, offset: Int): Int {
        require(firstLine >= 0)
        if (text.isEmpty()) return firstLine
        val lastCharacter = text.indexOfLast { it != '\n' }.coerceAtLeast(0)
        return firstLine + text.take(offset.coerceIn(0, lastCharacter)).count { it == '\n' }
    }

    fun contentOffset(text: String, offset: Int): Int =
        text.take(offset.coerceIn(0, text.length)).count { !it.isWhitespace() && it != '\uFEFF' }
}

/** Retain the audible sentence and the short generation queue. */
class SentenceTimeline {
    private val ranges = ArrayDeque<SentenceRange>()
    @Synchronized fun start(line: Int, first: Long) {
        require(ranges.isEmpty() || first >= ranges.last.first)
        ranges.addLast(SentenceRange(line, first))
    }
    @Synchronized fun finish(line: Int, end: Long) {
        val range = ranges.firstOrNull { it.line == line } ?: error("Sentence start is missing")
        require(end >= range.first)
        range.end = end
    }
    @Synchronized fun audible(played: Long): SentenceRange? {
        if (played <= 0) return null
        while (ranges.size > 1 && ranges.elementAt(1).first < played) ranges.removeFirst()
        return ranges.firstOrNull()?.takeIf { it.first < played }?.copy()
    }
}

/** Use monotonic milliseconds supplied by the caller. */
class SentenceTimer {
    @Volatile var deadline: Long? = null
        private set
    @Volatile var expired = false
        private set
    @Volatile var targetLine: Int? = null
        private set
    @Synchronized fun set(seconds: Long, now: Long) {
        require(seconds in 0..86400)
        deadline = if (seconds == 0L) null else Math.addExact(now, seconds * 1000)
        expired = false
        targetLine = null
    }
    @Synchronized fun observe(now: Long, audible: Int?): Boolean {
        val limit = deadline ?: return false
        if (!expired && now >= limit) {
            targetLine = audible ?: -1
            expired = true
            return true
        }
        return false
    }
    fun remaining(now: Long): Long? = deadline?.let { maxOf(0L, it - now) }
    /** Start the saved default for a new Play when no countdown time is left. */
    @Synchronized fun startDefault(minutes: Long, now: Long): Boolean {
        if (minutes <= 0) return false
        val limit = deadline
        if (limit != null && !expired && limit > now) return false
        set(minutes * 60, now)
        return true
    }
    @Synchronized fun retarget() {
        expired = false
        targetLine = null
    }
    /** Hold a future sentence near expiry before its samples enter AudioTrack. */
    fun holdFuture(now: Long, played: Long, sentenceFirst: Long, rate: Int): Boolean {
        val remaining = remaining(now) ?: return false
        if (expired || sentenceFirst <= played) return false
        return remaining <= (sentenceFirst - played) * 1000 / rate + 100
    }
}

/** Index sentence offsets once. Read only the text window near the audible sentence. */
class ReaderDocument(val file: File) {
    private val bytes: LongArray
    private val characters: LongArray
    private val content: LongArray
    private val words: LongArray
    val positionKey = BookPositions.key(file)
    val lineCount: Int get() = bytes.size
    val totalCharacters: Long
    val totalWords: Long
    init {
        val offsets = mutableListOf<Long>()
        val chars = mutableListOf<Long>()
        val contentOffsets = mutableListOf<Long>()
        val wordOffsets = mutableListOf<Long>()
        var byteCount = 0L
        var charCount = 0L
        var contentCount = 0L
        var wordCount = 0L
        val wordPattern = Regex("\\S+")
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                offsets.add(byteCount)
                chars.add(charCount)
                contentOffsets.add(contentCount)
                wordOffsets.add(wordCount)
                byteCount += line.toByteArray(Charsets.UTF_8).size + 1L
                charCount += line.length + 1L
                contentCount += line.count { !it.isWhitespace() }
                wordCount += wordPattern.findAll(line).count()
            }
        }
        bytes = offsets.toLongArray()
        characters = chars.toLongArray()
        content = contentOffsets.toLongArray()
        words = wordOffsets.toLongArray()
        totalCharacters = charCount
        totalWords = wordCount
    }
    /** Estimated listening seconds before [line], at the same 180 words per minute as [seekSeconds]. */
    fun elapsedSeconds(line: Int, speed: Float): Long {
        require(speed.isFinite() && speed > 0)
        val spoken = if (line >= words.size) totalWords else words[line.coerceAtLeast(0)]
        return (spoken / (WORDS_PER_SECOND * speed)).toLong()
    }
    fun totalSeconds(speed: Float): Long = elapsedSeconds(Int.MAX_VALUE, speed)

    companion object {
        const val WORDS_PER_SECOND = 3.0
        /** Format a duration as "1h 05m" or "12m". */
        fun formatEstimate(seconds: Long): String {
            val minutes = maxOf(0L, seconds) / 60
            return if (minutes >= 60) String.format(java.util.Locale.US, "%dh %02dm", minutes / 60, minutes % 60)
                else "${minutes}m"
        }
    }
    data class Window(val text: String, val activeStart: Int, val activeEnd: Int, val firstLine: Int = 0)
    fun lineForContentOffset(offset: Int): Int {
        require(offset >= 0)
        val found = content.binarySearch(offset.toLong())
        return (if (found >= 0) found else -found - 2).coerceIn(0, maxOf(0, content.lastIndex))
    }
    fun percent(line: Int): Double = if (line >= characters.size || totalCharacters == 0L) 100.0
        else characters[line] * 100.0 / totalCharacters
    /** Estimate ungenerated speech at 180 words per minute at normal speed. */
    fun seekSeconds(line: Int, seconds: Int, speed: Float): Int {
        require(speed.isFinite() && speed > 0)
        if (words.isEmpty()) return 0
        val current = line.coerceIn(0, words.lastIndex)
        if (seconds == 0) return current
        val target = words[current] + seconds * WORDS_PER_SECOND * speed
        val found = words.binarySearch(target.toLong())
        val candidate = (if (found >= 0) found else -found - 2).coerceIn(0, words.lastIndex)
        return if (seconds > 0) maxOf(candidate, minOf(current + 1, words.lastIndex))
            else minOf(candidate, maxOf(0, current - 1))
    }
    fun browseWindow(line: Int): Window = window(line, 40, 80)

    fun window(line: Int, before: Int = 2, after: Int = 24): Window {
        require(line >= 0)
        require(before >= 0 && after > 0)
        if (line >= bytes.size) return Window("", 0, 0)
        val first = maxOf(0, line - before)
        val result = StringBuilder()
        var start = 0
        var end = 0
        FileInputStream(file).use { input ->
            input.channel.position(bytes[first])
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                for (index in first until minOf(bytes.size, line + after)) {
                    val value = reader.readLine() ?: break
                    if (index == line) { start = result.length; end = start + value.length }
                    result.append(value).append('\n')
                    if (index >= line && result.length - start >= 12000) break
                }
            }
        }
        return Window(result.toString(), start, end, first)
    }
}

/** Store sentence positions in one private UTF-8 file, indexed by document content. */
object BookPositions {
    fun key(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun read(directory: File): Properties = Properties().apply {
        val file = File(directory, "book-positions.properties")
        if (file.exists()) file.reader(Charsets.UTF_8).use { load(it) }
    }
    @Synchronized fun load(directory: File, key: String): Int? =
        read(directory).getProperty("$key.line")?.let {
            requireNotNull(it.toIntOrNull()?.takeIf { line -> line >= 0 }) { "Invalid saved book position" }
        }

    @Synchronized fun save(directory: File, key: String, name: String, line: Int) {
        require(line >= 0 && key.matches(Regex("[a-f0-9]{64}")) && PlaybackText.validName(name))
        val values = read(directory)
        values.setProperty("$key.line", line.toString())
        values.setProperty("$key.file", name)
        val staged = File(directory, "book-positions.pending")
        staged.outputStream().use { output ->
            val writer = output.writer(Charsets.UTF_8)
            values.store(writer, "Saved sentence positions")
            writer.flush()
            output.fd.sync()
        }
        Files.move(staged.toPath(), File(directory, "book-positions.properties").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
