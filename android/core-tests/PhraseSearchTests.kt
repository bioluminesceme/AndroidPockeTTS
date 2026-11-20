package nl.user.pockettest

fun runPhraseSearchTests() {
    fun find(text: String, query: String) = PhraseSearch.find(text.lineSequence(), query)
    check(find("first second third", "first second").size == 1)
    check(find("second first", "first second").isEmpty())
    check(find("first extra second", "first second").isEmpty())
    check(find("First, SECOND!", "first second").size == 1)
    check(find("first\nsecond", "first second").single().line == 0)
    check(find("x first\nsecond", "first second").single().preview == "x first")
    check(find("a a a a", "a a").size == 3)
    check(find("a a b a a a b", "a a a b").size == 1)
    check(find("reading reader read", "read").size == 1)
    check(find("café naïve", "CAFÉ NAÏVE").size == 1)
    check(find("don't stop", "DON'T STOP").size == 1)
    check(PhraseSearch.find(sequenceOf("a a a a"), "a", 2).size == 2)
    var rejected = false
    try { find("text", "  ") } catch (_: IllegalArgumentException) { rejected = true }
    check(rejected)
    println("PASS: Exact adjacent word order, case and punctuation, UTF-8, sentence boundaries, overlapping matches, and search limits")
}
