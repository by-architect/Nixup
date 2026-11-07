package dev.nixup.manager.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Deletes [file] and, when it is a folder, what is in it, without following links: a link goes as a link, and what it
 * points to stays. An app's data (Termux's storage/shared, a lib folder) and the users' own folders in /run have links
 * into the rest of the phone, which Kotlin's deleteRecursively would empty.
 */
fun deleteTree(file: File) {
    val path = file.toPath()
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) file.listFiles().orEmpty().forEach { deleteTree(it) }
    runCatching { Files.deleteIfExists(path) }
}

/**
 * Writes [text] into [file] whole, as a new file put in its place: what reads it meanwhile (a service starting, a login,
 * another engine) finds the old one or the new one, never a part of it.
 */
fun writeWhole(file: File, text: String) {
    val next = File(file.parentFile, ".${file.name}.new")
    next.writeText(text)
    if (!next.renameTo(file)) {
        next.delete()
        file.writeText(text)
    }
}
