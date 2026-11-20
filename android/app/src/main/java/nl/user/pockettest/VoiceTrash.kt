package nl.user.pockettest

import java.io.File
import java.util.UUID

fun moveVoiceToTrash(profiles: File, directory: File): File {
    val root = profiles.canonicalFile
    val source = directory.canonicalFile
    require(source.parentFile == root && source.isDirectory) { "Invalid voice directory" }
    val trash = File(root.parentFile, "removed-voices")
    check(trash.mkdirs() || trash.isDirectory) { "Cannot create voice Trash" }
    val destination = File(trash, "${source.name}-${UUID.randomUUID()}")
    check(source.renameTo(destination)) { "Cannot move voice to Trash" }
    return destination
}
