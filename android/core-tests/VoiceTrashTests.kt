package nl.user.pockettest

import java.io.File
import java.nio.file.Files

fun runVoiceTrashTests() {
    val workspace = Files.createTempDirectory("voice-trash-test").toFile()
    val profiles = File(workspace, "profiles").apply { check(mkdir()) }
    val first = File(profiles, "voice-a").apply { check(mkdir()) }
    val other = File(profiles, "voice-b").apply { check(mkdir()) }
    File(first, "manifest.json").writeText("test manifest", Charsets.UTF_8)
    File(first, "speaker.safetensors").writeBytes(byteArrayOf(1, 2, 3))
    val removed = moveVoiceToTrash(profiles, first)
    check(!first.exists() && other.isDirectory)
    check(removed.parentFile == File(workspace, "removed-voices"))
    check(File(removed, "manifest.json").readText(Charsets.UTF_8) == "test manifest")
    check(File(removed, "speaker.safetensors").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    for (invalid in listOf(workspace, profiles, removed, File(profiles, "missing"))) {
        var rejected = false
        try { moveVoiceToTrash(profiles, invalid) } catch (_: IllegalArgumentException) { rejected = true }
        check(rejected && other.isDirectory && removed.isDirectory)
    }
    check(first.mkdir())
    val again = moveVoiceToTrash(profiles, first)
    check(again != removed && removed.isDirectory && again.isDirectory)
    println("PASS: Voice removal preserves files, retains other voices, and rejects invalid directories")
}
