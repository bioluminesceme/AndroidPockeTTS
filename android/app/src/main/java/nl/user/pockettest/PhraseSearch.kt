package nl.user.pockettest

import java.util.ArrayDeque
import java.util.Locale

object PhraseSearch {
    private val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    data class Match(val line: Int, val preview: String)
    fun find(lines: Sequence<String>, query: String, limit: Int = 100): List<Match> {
        require(query.length <= 500) { "Search text exceeds 500 characters" }
        require(limit > 0)
        val wanted = words.findAll(query).map { it.value.lowercase(Locale.ROOT) }.toList()
        require(wanted.isNotEmpty()) { "Enter one or more words" }
        val prefix = IntArray(wanted.size)
        for (index in 1 until wanted.size) {
            var count = prefix[index - 1]
            while (count > 0 && wanted[index] != wanted[count]) count = prefix[count - 1]
            if (wanted[index] == wanted[count]) count++
            prefix[index] = count
        }
        val recent = ArrayDeque<Match>()
        val results = mutableListOf<Match>()
        var count = 0
        for ((line, text) in lines.withIndex()) {
            for (word in words.findAll(text)) {
                recent.addLast(Match(line, text.take(240)))
                if (recent.size > wanted.size) recent.removeFirst()
                val token = word.value.lowercase(Locale.ROOT)
                while (count > 0 && token != wanted[count]) count = prefix[count - 1]
                if (token == wanted[count]) count++
                if (count == wanted.size) {
                    results.add(recent.first)
                    if (results.size == limit) return results
                    count = prefix[count - 1]
                }
            }
        }
        return results
    }
}
