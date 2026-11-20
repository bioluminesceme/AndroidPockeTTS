package nl.user.pockettest

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/** Convert local books to the sentence format used by playback. */
object BookImport {
    data class Chapter(val title: String, val line: Int)
    private const val XML_LIMIT = 24L * 1024 * 1024
    private const val ARCHIVE_LIMIT = 256L * 1024 * 1024
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val STRICT_WORD = "http://purl.oclc.org/ooxml/wordprocessingml/main"

    fun import(directory: File, input: InputStream, displayName: String): PlaybackText.Imported {
        val extension = displayName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        require(extension in setOf("txt", "epub", "docx")) { "Select a TXT, EPUB, or DOCX file" }
        val name = "book-${UUID.randomUUID()}.txt"
        if (extension == "txt") return PlaybackText.import(directory, input, name)
        val source = File.createTempFile("book-source-", ".zip", directory)
        try {
            source.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= ARCHIVE_LIMIT) { "Book archive exceeds 256 MiB" }
                    output.write(buffer, 0, count)
                }
            }
            ZipFile(source).use { zip ->
                val archive = Archive(zip)
                val chapters = mutableListOf<Chapter>()
                val imported = PlaybackText.store(directory, name) { accept ->
                    val output = Sentences(accept)
                    if (extension == "docx") docx(archive, output, chapters) else epub(archive, output, chapters)
                }
                DataOutputStream(File(directory, "$name.chapters").outputStream().buffered()).use { output ->
                    output.writeInt(chapters.size)
                    chapters.forEach { output.writeUTF(it.title.take(500)); output.writeInt(it.line) }
                }
                return imported
            }
        } finally {
            check(source.delete()) { "Cannot remove temporary book archive" }
        }
    }

    /** Return the chapter that contains [line]. A later entry wins when two start on the same line. */
    fun chapterAt(chapters: List<Chapter>, line: Int): Chapter? {
        var found: Chapter? = null
        for (chapter in chapters) if (chapter.line <= line && chapter.line >= (found?.line ?: Int.MIN_VALUE)) found = chapter
        return found
    }

    fun chapters(directory: File, name: String): List<Chapter> {
        require(PlaybackText.validName(name)) { "Invalid playback text file" }
        val file = File(directory, "$name.chapters")
        if (!file.exists()) return emptyList()
        return DataInputStream(file.inputStream().buffered()).use { input ->
            val size = input.readInt()
            require(size in 0..10000) { "Invalid chapter count" }
            List(size) {
                val title = input.readUTF()
                val line = input.readInt()
                require(title.isNotBlank() && line >= 0) { "Invalid chapter location" }
                Chapter(title, line)
            }
        }
    }

    private class Archive(private val zip: ZipFile) {
        private var readBytes = 0L
        init {
            val names = mutableSetOf<String>()
            val entries = zip.entries()
            var expanded = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.size < 20000 && names.add(entry.name)) { "Book has too many or duplicate ZIP entries" }
                require(!entry.name.startsWith('/') && '\\' !in entry.name && entry.name.split('/').none { it == ".." }) { "Invalid book archive path" }
                require(entry.size >= 0 && entry.size <= ARCHIVE_LIMIT) { "Invalid book entry size" }
                expanded += entry.size
                require(expanded <= ARCHIVE_LIMIT) { "Expanded book exceeds 256 MiB" }
            }
        }
        fun has(path: String): Boolean = zip.getEntry(path) != null
        fun parse(path: String, handler: DefaultHandler2) {
            val entry = requireNotNull(zip.getEntry(path)) { "Missing book entry: $path" }
            require(entry.size <= XML_LIMIT) { "Book XML exceeds 24 MiB: $path" }
            zip.getInputStream(entry).use { source ->
                val bounded = object : FilterInputStream(source) {
                    private var count = 0L
                    private fun counted(amount: Int): Int {
                        if (amount > 0) { count += amount; readBytes += amount }
                        require(count <= XML_LIMIT && readBytes <= ARCHIVE_LIMIT) { "Book XML limit exceeded" }
                        return amount
                    }
                    override fun read(): Int { val value = super.read(); counted(if (value < 0) 0 else 1); return value }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = counted(`in`.read(bytes, offset, length))
                }
                val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
                reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
                reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                reader.contentHandler = handler
                reader.entityResolver = handler
                reader.errorHandler = handler
                BufferedInputStream(bounded).use { input ->
                    validateProlog(input)
                    reader.parse(InputSource(input))
                }
            }
        }
        fun tree(path: String): Node {
            val root = Node("root", emptyMap())
            val stack = java.util.ArrayDeque<Node>().apply { add(root) }
            var count = 0
            parse(path, object : SafeXml() {
                override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                    require(stack.size < 128 && ++count <= 100000) { "Book XML structure is too large" }
                    val node = Node(local, (0 until attributes.length).associate { attributes.getLocalName(it) to attributes.getValue(it) })
                    stack.last.children.add(node); stack.last.parts.add(node); stack.add(node)
                }
                override fun characters(chars: CharArray, start: Int, length: Int) { stack.last.parts.add(String(chars, start, length)) }
                override fun endElement(uri: String, local: String, qName: String) { stack.removeLast() }
            })
            return root
        }
    }

    /** Android can omit custom entities without reporting a parser callback. */
    private fun validateProlog(input: BufferedInputStream) {
        input.mark(256 * 1024)
        val first = ByteArray(4)
        val count = input.read(first)
        input.reset()
        val bytes = first.map { it.toInt() and 255 }
        val charset = when {
            count >= 2 && (bytes[0] == 255 && bytes[1] == 254 || bytes[0] == 60 && bytes[1] == 0) -> Charsets.UTF_16LE
            count >= 2 && (bytes[0] == 254 && bytes[1] == 255 || bytes[0] == 0 && bytes[1] == 60) -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val reader = InputStreamReader(input, charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
        var characters = 0
        fun next(): Char {
            require(++characters <= 65536) { "Book XML header exceeds 65,536 characters" }
            val value = reader.read()
            require(value >= 0) { "Book XML has no root element" }
            return value.toChar()
        }
        fun until(end: String) {
            val tail = StringBuilder()
            while (true) {
                tail.append(next())
                if (tail.length > end.length) tail.deleteCharAt(0)
                if (tail.toString() == end) return
            }
        }
        while (true) {
            val character = next()
            if (character.isWhitespace() || character == '\uFEFF') continue
            require(character == '<') { "Invalid book XML header" }
            when (next()) {
                '?' -> until("?>")
                '!' -> when (next()) {
                    '-' -> { require(next() == '-') { "Invalid XML comment" }; until("-->") }
                    'D' -> {
                        require(buildString { repeat(6) { append(next()) } } == "OCTYPE") { "Invalid XML declaration" }
                        var quote: Char? = null
                        while (true) {
                            val value = next()
                            if (quote != null) { if (value == quote) quote = null }
                            else when (value) {
                                '\'', '"' -> quote = value
                                '[' -> throw IllegalArgumentException("Internal book DTDs are not supported")
                                '>' -> break
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("Invalid book XML declaration")
                }
                else -> { input.reset(); return }
            }
        }
    }

    private open class SafeXml : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            if (name !in setOf("html", "ncx")) throw SAXException("Unsupported book DTD: $name")
        }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        override fun startEntity(name: String) {
            if (name !in setOf("[dtd]", "amp", "lt", "gt", "quot", "apos")) throw SAXException("Custom XML entities are not supported: $name")
        }
        override fun skippedEntity(name: String) { throw SAXException("Unresolved XML entity: $name") }
        override fun warning(error: org.xml.sax.SAXParseException) { throw error }
        override fun error(error: org.xml.sax.SAXParseException) { throw error }
        override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
    }
    private class Node(val tag: String, val attributes: Map<String, String>) {
        val children = mutableListOf<Node>()
        val parts = mutableListOf<Any>()
        fun all(tag: String): List<Node> = children.flatMap { (if (it.tag == tag) listOf(it) else emptyList()) + it.all(tag) }
        private fun rawValue(): String = parts.joinToString("") { if (it is Node) it.rawValue() else it.toString() }
        fun value(): String = rawValue().replace(Regex("\\s+"), " ").trim()
        fun attr(name: String): String = attributes[name].orEmpty()
    }
    private class Sentences(private val accept: (String) -> Unit) {
        var line = 0
        fun block(value: String) {
            val text = value.replace('\u00a0', ' ').trim()
            if (text.isNotBlank()) TextFile.sentences(text.byteInputStream(Charsets.UTF_8)) { accept(it); line++ }
        }
    }

    private fun docx(archive: Archive, output: Sentences, chapters: MutableList<Chapter>) {
        val styles = if (archive.has("word/styles.xml")) archive.tree("word/styles.xml").all("style").associateBy { it.attr("styleId") } else emptyMap()
        fun heading(style: String, visited: Set<String> = emptySet()): Boolean {
            if (style in visited || visited.size >= 128) return false
            val node = styles[style]
            val level = node?.all("outlineLvl")?.firstOrNull()?.attr("val")?.toIntOrNull()
            if (level != null) return level in 0..8
            val name = node?.all("name")?.firstOrNull()?.attr("val").orEmpty()
            if (Regex("(?i)heading\\s*[1-9]").matches(name.ifBlank { style })) return true
            return node?.all("basedOn")?.firstOrNull()?.attr("val")?.let { heading(it, visited + style) } ?: false
        }
        var paragraph: StringBuilder? = null
        var isHeading = false
        var inText = false
        var skipped = 0
        var depth = 0
        var body = false
        archive.parse("word/document.xml", object : SafeXml() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                require(++depth <= 128) { "DOCX nesting exceeds 128 levels" }
                if (skipped > 0) { skipped++; return }
                if (uri !in setOf(WORD, STRICT_WORD)) return
                if (local in setOf("del", "moveFrom", "txbxContent")) { skipped = 1; return }
                if (local == "body") body = true
                if (!body) return
                when (local) {
                    "p" -> { paragraph = StringBuilder(); isHeading = false }
                    "pStyle" -> isHeading = heading(attributes.getValue(uri, "val").orEmpty())
                    "outlineLvl" -> isHeading = attributes.getValue(uri, "val")?.toIntOrNull()?.let { it in 0..8 } ?: false
                    "t" -> inText = true
                    "tab", "br", "cr" -> paragraph?.append(' ')
                }
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (inText && skipped == 0) {
                    paragraph?.append(chars, start, length)
                    require((paragraph?.length ?: 0) <= XML_LIMIT) { "DOCX paragraph is too large" }
                }
            }
            override fun endElement(uri: String, local: String, qName: String) {
                depth--
                if (skipped > 0) { skipped--; return }
                if (uri !in setOf(WORD, STRICT_WORD)) return
                when (local) {
                    "t" -> inText = false
                    "p" -> {
                        val value = paragraph?.toString().orEmpty().replace(Regex("\\s+"), " ").trim()
                        if (isHeading && value.isNotBlank()) chapters.add(Chapter(value.take(500), output.line))
                        output.block(value); paragraph = null
                    }
                    "body" -> body = false
                }
            }
        })
        require(chapters.size <= 10000) { "DOCX has too many headings" }
    }

    private data class Target(val path: String, val anchor: String)
    private fun target(base: String, href: String): Target {
        require(href.isNotBlank() && '\\' !in href) { "Invalid book reference" }
        val reference = URI(href)
        require(!reference.isAbsolute && reference.rawAuthority == null && reference.rawQuery == null) { "Book references must be local" }
        val resolved = URI(null, null, "/$base", null).resolve(reference).normalize()
        val path = resolved.path.removePrefix("/")
        require(path.isNotBlank() && path.split('/').none { it == ".." } && '\\' !in path) { "Book reference leaves the archive" }
        return Target(path, resolved.fragment.orEmpty())
    }

    private fun epub(archive: Archive, output: Sentences, chapters: MutableList<Chapter>) {
        val encrypted = if (archive.has("META-INF/encryption.xml")) archive.tree("META-INF/encryption.xml")
            .all("CipherReference").map { target("", it.attr("URI")).path }.toSet() else emptySet()
        val container = archive.tree("META-INF/container.xml")
        val packagePath = target("", container.all("rootfile").firstOrNull()?.attr("full-path").orEmpty()).path
        val packageXml = archive.tree(packagePath)
        val items = packageXml.all("manifest").flatMap { it.children }.filter { it.tag == "item" }.associateBy { it.attr("id") }
        val spine = requireNotNull(packageXml.all("spine").firstOrNull()) { "EPUB has no reading order" }
        val reading = spine.children.filter { it.tag == "itemref" && it.attr("linear") != "no" }.map {
            requireNotNull(items[it.attr("idref")]) { "EPUB reading order references a missing item" }
        }
        require(reading.isNotEmpty()) { "EPUB reading order is empty" }
        val navigation = mutableListOf<Pair<String, Target>>()
        val nav = items.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
        if (nav != null) {
            val navPath = target(packagePath, nav.attr("href")).path
            val toc = archive.tree(navPath).all("nav").firstOrNull { "toc" in it.attr("type").split(' ') }
            toc?.all("a")?.forEach { link ->
                if (link.value().isNotBlank()) navigation.add(link.value().take(500) to target(navPath, link.attr("href")))
            }
        } else {
            val ncx = items[spine.attr("toc")] ?: items.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
            if (ncx != null) {
                val ncxPath = target(packagePath, ncx.attr("href")).path
                archive.tree(ncxPath).all("navPoint").forEach { point ->
                    val label = point.children.firstOrNull { it.tag == "navLabel" }?.value().orEmpty()
                    val href = point.children.firstOrNull { it.tag == "content" }?.attr("src").orEmpty()
                    if (label.isNotBlank() && href.isNotBlank()) navigation.add(label.take(500) to target(ncxPath, href))
                }
            }
        }
        require(navigation.size <= 10000) { "EPUB has too many chapter links" }
        val locations = mutableMapOf<Target, Int>()
        val fallback = mutableListOf<Chapter>()
        for ((index, item) in reading.withIndex()) {
            require(item.attr("media-type") == "application/xhtml+xml") { "EPUB reading content must be XHTML" }
            val path = target(packagePath, item.attr("href")).path
            require(path !in encrypted) { "Encrypted EPUB text is not supported" }
            require(Target(path, "") !in locations) { "EPUB reading order contains duplicate content" }
            val first = output.line
            locations[Target(path, "")] = first
            val headings = mutableListOf<Chapter>()
            xhtml(archive, path, output, locations, headings)
            if (output.line > first) fallback.addAll(headings.ifEmpty { listOf(Chapter("Chapter ${index + 1}", first)) })
            else locations.entries.removeAll { it.key.path == path }
        }
        chapters.addAll(navigation.mapNotNull { (label, reference) -> locations[reference]?.takeIf { it < output.line }?.let { Chapter(label, it) } }.ifEmpty { fallback }.distinct())
        require(chapters.size <= 10000) { "EPUB has too many chapters" }
    }

    private fun xhtml(archive: Archive, path: String, output: Sentences, locations: MutableMap<Target, Int>, headings: MutableList<Chapter>) {
        val blockTags = setOf("p", "div", "section", "article", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th", "dt", "dd", "pre")
        val buffer = StringBuilder()
        var depth = 0
        var skipped = 0
        var body = false
        var heading = false
        fun flush() {
            val value = buffer.toString().replace(Regex("\\s+"), " ").trim()
            if (heading && value.isNotBlank()) headings.add(Chapter(value.take(500), output.line))
            output.block(value); buffer.setLength(0)
        }
        archive.parse(path, object : SafeXml() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                require(++depth <= 128) { "EPUB nesting exceeds 128 levels" }
                if (skipped > 0) { skipped++; return }
                if (local in setOf("head", "script", "style", "nav", "svg", "math") || attributes.getValue("hidden") != null || attributes.getValue("aria-hidden") == "true") { skipped = 1; return }
                if (local == "body") body = true
                if (!body) return
                if (local in blockTags) flush()
                val id = attributes.getValue("id") ?: attributes.getValue("http://www.w3.org/XML/1998/namespace", "id")
                if (id != null) locations.putIfAbsent(Target(path, id), output.line)
                if (local.matches(Regex("h[1-6]"))) heading = true
                if (local == "br" || local == "hr") buffer.append(' ')
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (body && skipped == 0) {
                    buffer.append(chars, start, length)
                    require(buffer.length <= XML_LIMIT) { "EPUB text block is too large" }
                }
            }
            override fun endElement(uri: String, local: String, qName: String) {
                depth--
                if (skipped > 0) { skipped--; return }
                if (body && (local in blockTags || local == "body")) flush()
                if (local.matches(Regex("h[1-6]"))) heading = false
                if (local == "body") body = false
            }
        })
    }
}
