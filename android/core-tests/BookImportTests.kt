package nl.user.pockettest

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun runBookImportTests() {
    val directory = Files.createTempDirectory("pocket-books-test").toFile()
    fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            entries.forEach { (path, text) ->
                output.putNextEntry(ZipEntry(path)); output.write(text.toByteArray(Charsets.UTF_8)); output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
    fun load(entries: Map<String, String>, name: String): PlaybackText.Imported =
        BookImport.import(directory, zip(entries).inputStream(), name)
    fun lines(book: PlaybackText.Imported): List<String> = PlaybackText.open(directory, book.name).use { it.readLines() }
    fun reject(entries: Map<String, String>, name: String) {
        val before = directory.listFiles()!!.map { it.name }.toSet()
        var rejected = false
        try { load(entries, name) } catch (_: Exception) { rejected = true }
        check(rejected) { "Invalid book was accepted: $name" }
        check(directory.listFiles()!!.map { it.name }.toSet() == before) { "Failed import left files or changed the previous book" }
    }
    val wordNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    fun word(body: String): String = "<w:document xmlns:w=\"$wordNs\"><w:body>$body</w:body></w:document>"
    val styles = """<w:styles xmlns:w="$wordNs"><w:style w:styleId="Custom"><w:basedOn w:val="Heading1"/></w:style><w:style w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style></w:styles>"""
    val docx = mapOf("word/styles.xml" to styles, "word/document.xml" to word("""
        <w:p><w:pPr><w:pStyle w:val="Custom"/></w:pPr><w:r><w:t>First chapter</w:t></w:r></w:p>
        <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> café.</w:t></w:r><w:del><w:r><w:delText>Deleted text.</w:delText></w:r></w:del><w:ins><w:r><w:t xml:space="preserve"> Accepted text.</w:t></w:r></w:ins></w:p>
        <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Table cell.</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
        <w:p><w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:r><w:t>Second chapter</w:t></w:r></w:p>
        <w:p><w:r><w:t>A new</w:t><w:tab/><w:t>line.</w:t><w:br/><w:t>Last sentence!</w:t></w:r></w:p>
    """))
    val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    fun html(body: String): String = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Do not read the title</title></head><body>$body</body></html>"
    val epub = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to container,
        "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="second" href="second.xhtml" media-type="application/xhtml+xml"/><item id="first" href="text/first%20chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="toc.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="extra" href="extra.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="first"/><itemref idref="extra" linear="no"/><itemref idref="second"/></spine></package>""",
        "OPS/toc.xhtml" to html("""<nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc"><ol><li><a href="text/first%20chapter.xhtml#one">First chapter</a><ol><li><a href="text/first%20chapter.xhtml#two">First <b>subsection</b></a></li></ol></li><li><a href="second.xhtml#end">Second chapter</a></li></ol></nav>"""),
        "OPS/second.xhtml" to html("<h1 id=\"end\">Two</h1><p>Final chapter.</p>"),
        "OPS/extra.xhtml" to html("<p>Do not read the appendix.</p>"),
        "OPS/text/first chapter.xhtml" to html("<h1 id=\"one\">One</h1><p>Hello <em>café</em>. Next sentence!</p><h2 id=\"two\">Subsection</h2><p>More&#160;text.</p><script>Do not read scripts.</script><p hidden=\"hidden\">Hidden text.</p>")
    )
    try {
        val first = load(docx, "example.DOCX")
        check(lines(first) == listOf("First chapter", "Hello café.", "Accepted text.", "Table cell.", "Second chapter", "A new line.", "Last sentence!")) { lines(first) }
        check(BookImport.chapters(directory, first.name) == listOf(BookImport.Chapter("First chapter", 0), BookImport.Chapter("Second chapter", 4)))
        val found = listOf(BookImport.Chapter("One", 2), BookImport.Chapter("Part", 5), BookImport.Chapter("Two", 5), BookImport.Chapter("Three", 9))
        check(BookImport.chapterAt(found, 1) == null)
        check(BookImport.chapterAt(found, 2)?.title == "One" && BookImport.chapterAt(found, 4)?.title == "One")
        check(BookImport.chapterAt(found, 5)?.title == "Two" && BookImport.chapterAt(found, 8)?.title == "Two")
        check(BookImport.chapterAt(found, 100)?.title == "Three" && BookImport.chapterAt(emptyList(), 3) == null)
        val strict = load(mapOf("word/document.xml" to word("<w:p><w:r><w:t>Strict document.</w:t></w:r></w:p>").replace(wordNs, "http://purl.oclc.org/ooxml/wordprocessingml/main")), "strict.docx")
        check(lines(strict) == listOf("Strict document."))
        check(BookImport.chapters(directory, strict.name).isEmpty())
        println("PASS: DOCX runs, UTF-8 text, tables, inserted text, deleted text, strict namespaces, and heading locations")

        val book = load(epub, "example.epub")
        check(lines(book) == listOf("One", "Hello café.", "Next sentence!", "Subsection", "More text.", "Two", "Final chapter.")) { lines(book) }
        val chapters = BookImport.chapters(directory, book.name)
        check(chapters == listOf(BookImport.Chapter("First chapter", 0), BookImport.Chapter("First subsection", 3), BookImport.Chapter("Second chapter", 5))) { chapters }
        val indexed = ReaderDocument(File(directory, book.name))
        for (chapter in chapters) {
            PlaybackText.openAt(directory, book.name, chapter.line).use { check(it.readLine() == lines(book)[chapter.line]) }
            val window = indexed.window(chapter.line)
            check(window.text.substring(window.activeStart, window.activeEnd) == lines(book)[chapter.line])
        }
        check(PlaybackText.position(directory, book.name, 100.0).line == 7)
        check(PlaybackText.open(directory, book.name).use { PhraseSearch.find(it.lineSequence(), "hello café") }.single().line == 1)
        check(lines(first).first() == "First chapter")
        println("PASS: EPUB spine order, chapter fragments, nested navigation, escaped paths, skipped content, search, playback positions, and text windows")

        val html5 = epub.mapValues { (path, value) -> if (path.endsWith(".xhtml")) "<!DOCTYPE html>" + value else value }
        val modern = load(html5, "html5.epub")
        check(lines(modern) == lines(book))
        check(BookImport.chapters(directory, modern.name) == chapters)
        println("PASS: HTML5 doctype in EPUB navigation and chapter files preserves text and chapter locations")

        val ncx = epub.toMutableMap()
        ncx["OPS/book.opf"] = """<package><manifest><item id="first" href="text/first%20chapter.xhtml" media-type="application/xhtml+xml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="first"/></spine></package>"""
        ncx["OPS/toc.ncx"] = """<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx><navMap><navPoint><navLabel><text>NCX chapter</text></navLabel><content src="text/first%20chapter.xhtml#one"/><navPoint><navLabel><text>Nested NCX chapter</text></navLabel><content src="text/first%20chapter.xhtml#two"/></navPoint></navPoint></navMap></ncx>"""
        ncx["OPS/text/first chapter.xhtml"] = """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">""" + ncx.getValue("OPS/text/first chapter.xhtml")
        val old = load(ncx, "version2.epub")
        check(BookImport.chapters(directory, old.name).map { it.line } == listOf(0, 3))
        check(BookImport.chapters(directory, old.name).map { it.title } == listOf("NCX chapter", "Nested NCX chapter"))
        val obfuscatedFont = epub + ("META-INF/encryption.xml" to "<encryption><EncryptedData><CipherData><CipherReference URI=\"OPS/font.otf\"/></CipherData></EncryptedData></encryption>")
        check(lines(load(obfuscatedFont, "font.epub")) == lines(book))
        val fallback = ncx.toMutableMap()
        fallback["OPS/book.opf"] = ncx.getValue("OPS/book.opf").replace(" toc=\"ncx\"", "").replace("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>", "")
        check(BookImport.chapters(directory, load(fallback, "fallback.epub").name).map { it.title } == listOf("One", "Subsection"))
        val txt = BookImport.import(directory, "Plain text. Another sentence.".byteInputStream(), "plain.txt")
        check(lines(txt) == listOf("Plain text.", "Another sentence."))
        check(BookImport.chapters(directory, txt.name).isEmpty())
        println("PASS: EPUB NCX navigation, heading fallback, persisted chapters, independent imports, and TXT compatibility")

        reject(docx, "old.doc")
        reject(mapOf("word/document.xml" to word("<w:p/>")), "empty.docx")
        reject(mapOf("word/document.xml" to "<broken>"), "malformed.docx")
        reject(mapOf("word/document.xml" to "<!DOCTYPE document [<!ENTITY secret SYSTEM 'file:///private'>]>" + word("<w:p><w:r><w:t>&secret;</w:t></w:r></w:p>")), "external.docx")
        reject(epub + ("META-INF/encryption.xml" to "<encryption><EncryptedData><CipherData><CipherReference URI=\"OPS/second.xhtml\"/></CipherData></EncryptedData></encryption>"), "encrypted.epub")
        reject(epub + ("OPS/second.xhtml" to "<!DOCTYPE html SYSTEM 'http://example.invalid/dtd' [<!ENTITY secret SYSTEM 'file:///private'>]>" + html("<p>&secret;</p>")), "entity.epub")
        reject(epub + ("OPS/second.xhtml" to "<!DOCTYPE html [<!ENTITY custom 'hidden replacement'>]>" + html("<p>&custom;</p>")), "internal-entity.epub")
        reject(epub + ("../escape" to "Invalid archive path"), "traversal.epub")
        reject(epub + ("META-INF/container.xml" to container.replace("OPS/book.opf", "../../escape.opf")), "escape.epub")
        reject(epub + ("OPS/book.opf" to epub.getValue("OPS/book.opf").replace("idref=\"second\"", "idref=\"missing\"")), "missing.epub")
        reject(epub + ("OPS/toc.xhtml" to epub.getValue("OPS/toc.xhtml").replace("href=\"second.xhtml#end\"", "href=\"https://example.invalid/book\"")), "remote.epub")
        reject(mapOf("word/document.xml" to word("<w:p><w:r><w:t>${"x".repeat(TextFile.SENTENCE_LIMIT + 1)}</w:t></w:r></w:p>")), "oversized.docx")
        var corruptRejected = false
        try { BookImport.import(directory, "not a ZIP".byteInputStream(), "corrupt.epub") } catch (_: java.util.zip.ZipException) { corruptRejected = true }
        check(corruptRejected)
        check(lines(book).last() == "Final chapter.")
        println("PASS: Invalid formats, empty books, malformed XML, external entities, encryption, unsafe paths, remote links, oversized sentences, and corrupt ZIP rejection")
    } finally {
        directory.listFiles()?.forEach { check(it.delete()) }
        check(directory.delete())
    }
}
