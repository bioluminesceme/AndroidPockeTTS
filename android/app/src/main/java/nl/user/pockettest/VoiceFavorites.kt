package nl.user.pockettest

import java.util.Locale

object VoiceFavorites {
    fun toggle(favorites: Set<String>, id: String): Set<String> {
        require(id.isNotBlank()) { "Voice ID is empty" }
        return if (id in favorites) favorites - id else favorites + id
    }

    fun <T> sorted(voices: List<T>, favorites: Set<String>, id: (T) -> String, name: (T) -> String): List<T> =
        voices.sortedWith(compareBy<T> { id(it) !in favorites }
            .thenBy { name(it).trim().lowercase(Locale.ROOT) }.thenBy { id(it) })
}
