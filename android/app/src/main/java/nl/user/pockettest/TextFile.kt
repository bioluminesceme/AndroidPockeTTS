package nl.user.pockettest

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Locale

object TextFile {
    const val SENTENCE_LIMIT = 16384

    fun sentences(input: InputStream, accept: (String) -> Unit) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val pending = StringBuilder()
        val boundaries = BreakIterator.getSentenceInstance(Locale.US)
        val whitespace = Regex("\\s+")
        var first = true
        var foundText = false
        fun emit(value: String) {
            val sentence = value.trim().replace(whitespace, " ")
            require(sentence.length <= SENTENCE_LIMIT) { "A sentence exceeds 16,384 characters. Add sentence punctuation." }
            if (sentence.isNotEmpty()) { accept(sentence); foundText = true }
        }
        InputStreamReader(input, decoder).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val character = buffer[index]
                    if (!(first && character == '\uFEFF')) {
                        require(!character.isISOControl() || character in "\r\n\t") { "Select a UTF-8 plain text file" }
                        pending.append(character)
                    }
                    first = false
                }
                boundaries.setText(pending.toString())
                var start = boundaries.first()
                var end = boundaries.next()
                while (end != BreakIterator.DONE && end < pending.length) {
                    emit(pending.substring(start, end))
                    start = end
                    end = boundaries.next()
                }
                pending.delete(0, start)
                if (pending.all { it.isWhitespace() }) pending.setLength(0)
                require(pending.length <= SENTENCE_LIMIT) { "A sentence exceeds 16,384 characters. Add sentence punctuation." }
            }
            emit(pending.toString())
        }
        require(foundText) { "TXT file is empty" }
    }
}
