package nl.user.pockettest

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object PlaybackText {
    data class Imported(val name: String, val preview: String, val characters: Long)
    data class Position(val line: Int, val percent: Double, val preview: String)

    fun position(directory: File, name: String, percent: Double): Position {
        require(percent.isFinite() && percent in 0.0..100.0) { "Enter a percentage from 0 to 100" }
        var total = 0L
        open(directory, name).use { reader -> reader.forEachLine { total += it.length + 1L } }
        val target = (total * percent / 100.0).toLong()
        var offset = 0L
        var line = 0
        val preview = StringBuilder()
        var selected = false
        open(directory, name).use { reader ->
            while (true) {
                val sentence = reader.readLine() ?: break
                if (!selected && offset + sentence.length + 1L > target) selected = true
                if (selected) {
                    preview.append((sentence + "\n").take(2000 - preview.length))
                    if (preview.length == 2000) break
                } else {
                    offset += sentence.length + 1L
                    line++
                }
            }
        }
        return Position(line, if (total == 0L) 100.0 else offset * 100.0 / total, preview.toString())
    }

    fun openAt(directory: File, name: String, line: Int): java.io.BufferedReader {
        require(line >= 0) { "Invalid starting position" }
        val reader = open(directory, name)
        try {
            repeat(line) { require(reader.readLine() != null) { "Starting position exceeds the end of the file" } }
            return reader
        } catch (error: Exception) { reader.close(); throw error }
    }

    fun write(directory: File, text: String): String {
        return import(directory, text.byteInputStream(Charsets.UTF_8), "playback-input.txt").name
    }

    fun import(directory: File, input: InputStream, name: String = "selected-book.txt"): Imported {
        return store(directory, name) { accept -> TextFile.sentences(input, accept) }
    }

    fun store(directory: File, name: String, produce: ((String) -> Unit) -> Unit): Imported {
        require(validName(name)) { "Invalid playback text file" }
        val staged = File.createTempFile("text-import-", ".tmp", directory)
        val preview = StringBuilder()
        var characters = 0L
        try {
            staged.bufferedWriter(Charsets.UTF_8).use { writer ->
                produce { sentence ->
                    require(sentence.isNotBlank() && '\n' !in sentence && '\r' !in sentence && sentence.length <= TextFile.SENTENCE_LIMIT) { "Invalid document sentence" }
                    writer.write(sentence)
                    writer.write("\n")
                    characters += sentence.length + 1L
                    if (preview.length < 2000) preview.append((sentence + "\n").take(2000 - preview.length))
                }
            }
            require(characters > 0) { "Document has no readable text" }
            Files.move(staged.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return Imported(name, preview.toString(), characters)
        } finally {
            if (staged.exists()) check(staged.delete()) { "Cannot remove incomplete text import" }
        }
    }

    fun open(directory: File, name: String): java.io.BufferedReader {
        require(validName(name)) { "Invalid playback text file" }
        return File(directory, name).bufferedReader(Charsets.UTF_8)
    }

    fun validName(name: String): Boolean = name in setOf("playback-input.txt", "selected-book.txt") ||
        Regex("book-[a-f0-9-]{36}\\.txt").matches(name)
}
