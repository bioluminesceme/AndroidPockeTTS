package nl.user.pockettest

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class Voice(val directory: File, val manifest: JSONObject) {
    val name: String get() = manifest.getString("name")
    val settings: String get() = manifest.getJSONObject("settings").toString()
    val speaker: File get() = File(directory, "speaker.safetensors")
}

class LocalData(private val context: Context) {
    val contract = JSONObject(context.assets.open("runtime-contract.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    val configName = contract.getJSONObject("identity").getJSONObject("configuration").getString("id")
    val runtime = File(context.filesDir, "runtime")
    private val profiles = File(context.filesDir, "profiles").apply { mkdirs() }

    fun voices(): List<Voice> = profiles.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.map {
        Voice(it, readJson(File(it, "manifest.json")))
    }

    fun removeVoice(voice: Voice) {
        moveVoiceToTrash(profiles, voice.directory)
    }

    private fun extract(uri: Uri, limits: Map<String, Long>): File {
        val stage = File(context.cacheDir, "import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val found = mutableSetOf<String>()
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open the selected file" }
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val limit = requireNotNull(limits[entry.name]) { "Unexpected ZIP entry: ${entry.name}" }
                require(found.add(entry.name)) { "Duplicate ZIP entry: ${entry.name}" }
                require(entry.method == ZipEntry.STORED && !entry.isDirectory) { "ZIP entries must use stored files" }
                require(entry.size in 0..limit) { "ZIP entry exceeds its size limit" }
                val output = File(stage, entry.name)
                check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                var total = 0L
                output.outputStream().use { target ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit) { "ZIP entry exceeds its size limit" }
                        target.write(buffer, 0, count)
                    }
                }
                require(total == entry.size) { "ZIP entry size differs" }
                zip.closeEntry()
            }
        }
        require(found == limits.keys) { "ZIP entries are missing" }
        return stage
    }

    fun importRuntime(uri: Uri): String {
        val files = contract.getJSONObject("files")
        val limits = files.keys().asSequence().associateWith { files.getJSONObject(it).getLong("size") }
        val stage = extract(uri, limits)
        verifyRuntime(stage)
        if (runtime.exists()) verifyRuntime(runtime)
        else check(stage.renameTo(runtime)) { "Cannot save the runtime" }
        return "Runtime imported and checksums verified"
    }

    fun verifyRuntime(directory: File = runtime) {
        val files = contract.getJSONObject("files")
        for (name in files.keys()) {
            val identity = files.getJSONObject(name)
            val file = File(directory, name)
            require(file.isFile && file.length() == identity.getLong("size")) { "Runtime file is missing or has a different size: $name" }
            require(hash(file) == identity.getString("sha256")) { "Runtime checksum differs: $name" }
        }
    }

    fun importVoice(uri: Uri): String {
        val stage = extract(uri, mapOf("manifest.json" to 65536L, "speaker.safetensors" to 100L * 1024 * 1024))
        val voice = validateVoice(stage)
        val speakerHash = hash(voice.speaker)
        val destination = File(profiles, hash(File(stage, "manifest.json")))
        if (destination.exists()) validateVoice(destination)
        else check(stage.renameTo(destination)) { "Cannot save the voice profile" }
        return "${voice.name}: speaker SHA-256 $speakerHash"
    }

    fun validateVoice(directory: File): Voice {
        require(directory.canonicalFile.parentFile == profiles.canonicalFile || directory.canonicalFile.parentFile == context.cacheDir.canonicalFile) { "Invalid profile directory" }
        val manifest = readJson(File(directory, "manifest.json"))
        keys(manifest, setOf("schema", "profile_version", "name", "language", "adapter", "adapter_version", "producer_runtime", "model", "configuration", "tokenizer", "speaker", "settings"))
        require(manifest.get("schema") == "android-custom-tts.voice-profile" && manifest.get("profile_version") == 1) { "Incompatible profile version" }
        require(manifest.get("language") == "en-US" && manifest.get("name") is String && manifest.getString("name").isNotBlank()) { "Invalid profile name or language" }
        val identity = contract.getJSONObject("identity")
        for (key in identity.keys()) require(same(manifest.get(key), identity.get(key))) { "Incompatible profile identity: $key" }
        val provenance = manifest.getJSONObject("producer_runtime")
        keys(provenance, setOf("id", "sha256"))
        require(provenance.get("id") is String && provenance.getString("sha256").matches(Regex("[0-9a-f]{64}"))) { "Invalid producer provenance" }
        val speaker = manifest.getJSONObject("speaker")
        keys(speaker, setOf("path", "format", "sha256", "size_bytes", "tensors"))
        require(speaker.get("path") == "speaker.safetensors" && speaker.get("format") == "pocket-tts-voice-state-safetensors") { "Incompatible speaker format" }
        val source = File(directory, "speaker.safetensors")
        require(source.isFile && source.length() in 1..100L * 1024 * 1024 && source.length() == speaker.getLong("size_bytes")) { "Speaker size differs" }
        require(hash(source) == speaker.getString("sha256")) { "Speaker checksum differs" }
        require(same(validateTensors(source), speaker.getJSONObject("tensors"))) { "Speaker tensor identity differs" }
        val settings = manifest.getJSONObject("settings")
        keys(settings, setOf("temperature", "sampler_decode_steps", "eos_threshold", "max_tokens", "frames_after_eos", "quantize"))
        for (key in listOf("temperature", "eos_threshold")) {
            require(settings.get(key) is Number && settings.getDouble(key).isFinite()) { "Invalid setting: $key" }
        }
        require(settings.getDouble("temperature") >= 0) { "Temperature must be nonnegative" }
        for (key in listOf("sampler_decode_steps", "max_tokens")) require(integer(settings.get(key)) && settings.getLong(key) > 0) { "Invalid setting: $key" }
        require(settings.isNull("frames_after_eos") || (integer(settings.get("frames_after_eos")) && settings.getLong("frames_after_eos") >= 0)) { "Invalid frames_after_eos" }
        require(settings.get("quantize") == false) { "This native interface requires quantize=false" }
        return Voice(directory, manifest)
    }

    private fun validateTensors(file: File): JSONObject = RandomAccessFile(file, "r").use { data ->
        val length = java.lang.Long.reverseBytes(data.readLong())
        require(length in 1..1048576 && length <= file.length() - 8) { "Invalid tensor header length" }
        val bytes = ByteArray(length.toInt()).also { data.readFully(it) }
        val header = JSONObject(decode(bytes))
        val start = 8 + length
        val names = header.keys().asSequence().filter { it != "__metadata__" }.toSet()
        val allowed = mutableSetOf<String>()
        val spans = mutableListOf<Pair<Long, Long>>()
        var time = 0L
        for (layer in 0..5) {
            val prefix = "transformer.layers.$layer.self_attn/"
            for (suffix in listOf("cache", "offset", "pad")) {
                val name = prefix + suffix
                if (suffix == "pad" && !header.has(name)) continue
                allowed.add(name)
                val tensor = header.getJSONObject(name)
                keys(tensor, setOf("dtype", "shape", "data_offsets"))
                val shape = tensor.getJSONArray("shape")
                val offsets = tensor.getJSONArray("data_offsets")
                require(offsets.length() == 2 && integer(offsets.get(0)) && integer(offsets.get(1))) { "Invalid tensor offsets" }
                val begin = offsets.getLong(0)
                val end = offsets.getLong(1)
                require(begin >= 0 && end >= begin && end <= file.length() - start) { "Tensor payload exceeds file" }
                if (suffix == "cache") {
                    require(tensor.get("dtype") == "F32" && shape.length() == 5) { "Invalid cache type or shape" }
                    for (index in 0 until shape.length()) require(integer(shape.get(index))) { "Invalid cache dimension" }
                    val current = shape.getLong(2)
                    require(shape.getLong(0) == 2L && shape.getLong(1) == 1L && current > 0 && shape.getLong(3) == 16L && shape.getLong(4) == 64L) { "Incompatible cache shape" }
                    require(layer == 0 || time == current) { "Cache lengths differ" }
                    time = current
                    require(end - begin == Math.multiplyExact(current, 8192L)) { "Cache byte size differs" }
                } else {
                    require(tensor.get("dtype") == "I64" && shape.length() == 1 && shape.get(0) == 1 && end - begin == 8L) { "Invalid offset or pad" }
                    data.seek(start + begin)
                    val value = java.lang.Long.reverseBytes(data.readLong())
                    require(if (suffix == "pad") value == 0L else value in 0..time) { "Invalid offset or pad value" }
                }
                spans.add(begin to end)
            }
        }
        require(names == allowed) { "Unsupported speaker tensors" }
        var end = 0L
        for (span in spans.sortedBy { it.first }) {
            require(span.first == end) { "Tensor payload has gaps or overlaps" }
            end = span.second
        }
        require(end == file.length() - start) { "Tensor payload length differs" }
        JSONObject().put("layers", 6).put("time", time).put("kv_heads", 16).put("head_dim", 64)
    }

    companion object {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { source ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        private fun readJson(file: File): JSONObject {
            require(file.length() in 1..65536) { "Invalid manifest size" }
            return JSONObject(decode(file.readBytes()))
        }
        private fun keys(value: JSONObject, expected: Set<String>) {
            require(value.keys().asSequence().toSet() == expected) { "Unexpected JSON fields" }
        }
        private fun integer(value: Any): Boolean = value is Int || value is Long
        private fun same(left: Any, right: Any): Boolean = when {
            left is JSONObject && right is JSONObject -> {
                val names = left.keys().asSequence().toSet()
                names == right.keys().asSequence().toSet() && names.all { same(left.get(it), right.get(it)) }
            }
            left is JSONArray && right is JSONArray -> left.length() == right.length() && (0 until left.length()).all { same(left.get(it), right.get(it)) }
            left is Number && right is Number -> left.toDouble() == right.toDouble()
            else -> left == right
        }
    }
}
