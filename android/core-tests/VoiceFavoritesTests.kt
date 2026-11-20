package nl.user.pockettest

fun runVoiceFavoritesTests() {
    data class Entry(val id: String, val name: String)
    val voices = listOf(Entry("alba", "Alba"), Entry("frank", "Frank"), Entry("other-frank", "Frank"), Entry("zoe", "Zoe"))
    val initial = setOf("zoe", "missing")
    val favorites = VoiceFavorites.toggle(initial, "frank")
    check(initial == setOf("zoe", "missing"))
    val sorted = VoiceFavorites.sorted(voices.reversed(), favorites, { it.id }, { it.name })
    check(sorted.map { it.id } == listOf("frank", "zoe", "alba", "other-frank"))
    check(sorted.single { it.id == "frank" } == voices[1])
    check(VoiceFavorites.toggle(favorites, "frank") == initial)
    check(VoiceFavorites.sorted(voices.reversed(), emptySet(), { it.id }, { it.name }) == voices)
    val varied = listOf(Entry("z", "zoe"), Entry("b", "Beta"), Entry("a", " alba"))
    check(VoiceFavorites.sorted(varied, emptySet(), { it.id }, { it.name }).map { it.id } == listOf("a", "b", "z"))
    check(VoiceFavorites.sorted(varied, setOf("z", "b"), { it.id }, { it.name }).map { it.id } == listOf("b", "z", "a"))
    check(VoiceFavorites.sorted(emptyList<Entry>(), favorites, { it.id }, { it.name }).isEmpty())
    check(runCatching { VoiceFavorites.toggle(favorites, " ") }.exceptionOrNull() is IllegalArgumentException)
    println("PASS: Favorite toggle, alphabetical groups, case and space handling, duplicate names, missing profiles, and retained voice identity")
}
