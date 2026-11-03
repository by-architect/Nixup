package dev.seraph.manager

import android.net.Uri
import android.provider.DocumentsContract

/**
 * The config a build reads, which the module keeps in /data/adb/seraph/source: a file on the phone, wherever the user
 * keeps it (a configuration.nix, or a flake.nix whose folder is the flake), or a flake's address, like
 * github:owner/repo; either may end in #name, the flake's configuration to build. The system's file picker gives a
 * document, not a path, so a picked file is turned into the path root reads it at.
 */
object ConfigSource {
    const val FILE = "/data/adb/seraph/source"
    private const val STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"
    private const val MEDIA = "com.android.providers.media.documents"
    private const val PHONE_STORAGE = "/storage/emulated/0"

    /**
     * The path of a picked file on the phone's storage or an SD card, as its document says it; null when its
     * document does not say (see [mediaId]) or it is not on the phone (a cloud drive's).
     */
    fun pathOf(uri: Uri): String? {
        val id = documentId(uri) ?: return null
        return when (uri.authority) {
            STORAGE -> storagePath(id)
            DOWNLOADS -> if (id.startsWith("raw:")) id.removePrefix("raw:") else null
            else -> null
        }
    }

    /** The path of a folder the system's folder picker gave (a tree), on the phone's storage or an SD card; null for another. */
    fun treePath(uri: Uri): String? {
        if (uri.authority != STORAGE) return null
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return storagePath(id).trimEnd('/')
    }

    /** A document of the phone's storage (primary:Download/x) as its path. */
    private fun storagePath(id: String): String {
        val volume = id.substringBefore(':')
        val inside = id.substringAfter(':', "")
        return when (volume) {
            "primary" -> "$PHONE_STORAGE/$inside"
            "home" -> "$PHONE_STORAGE/Documents/$inside"
            else -> "/storage/$volume/$inside"
        }
    }

    /** The file's number in Android's media store, when its document names it so (Downloads, Documents); root asks the store for its path. */
    fun mediaId(uri: Uri): Long? {
        val id = documentId(uri) ?: return null
        return when (uri.authority) {
            DOWNLOADS -> id.removePrefix("msf:").toLongOrNull()
            MEDIA -> id.substringAfter(':').toLongOrNull()
            else -> null
        }
    }

    /** The root command that prints the media store's path for [id], and what it prints, as `_data=<path>`. */
    fun mediaPathCommand(id: Long) = "content query --uri content://media/external/file --projection _data --where '_id=$id'"
    fun mediaPathIn(said: String): String? = said.substringAfter("_data=", "").trim().ifEmpty { null }

    /**
     * Whether [source] is one a build can read: a .nix file's full path (spaces are fine, quotes and other shell
     * characters are not), or a flake's address; either with an optional #name.
     */
    fun isValid(source: String): Boolean {
        if (source.isBlank() || source.any { it == '\n' || it == '\'' || it == '"' || it == '\\' || it == '`' || it == '$' }) return false
        val where = source.substringBefore('#')
        val name = source.substringAfter('#', "")
        if (source.contains('#') && !NAME.matches(name)) return false
        return if (where.startsWith("/")) where.endsWith(".nix") else ADDRESS.matches(where)
    }

    /** Whether [source] is a file on the phone (else, a flake's address). */
    fun isFile(source: String) = source.startsWith("/")

    /** The folder a file of [source] is in, as the Files app's document, to open it; null for an address. */
    fun folderDocument(source: String): Uri? {
        if (!isFile(source)) return null
        val folder = source.substringBefore('#').substringBeforeLast('/')
        val id = when {
            folder == PHONE_STORAGE -> "primary:"
            folder.startsWith("$PHONE_STORAGE/") -> "primary:" + folder.removePrefix("$PHONE_STORAGE/")
            folder.startsWith("/storage/") -> folder.removePrefix("/storage/").let { "${it.substringBefore('/')}:${it.substringAfter('/', "")}" }
            else -> return null
        }
        return DocumentsContract.buildDocumentUri(STORAGE, id)
    }

    /** How [source] is shown: the phone's storage by name rather than by its path. */
    fun shown(source: String, storageName: String) =
        if (source.startsWith("$PHONE_STORAGE/")) "$storageName/" + source.removePrefix("$PHONE_STORAGE/") else source

    /** [text] as one word for the shell. */
    fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    private fun documentId(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        null
    }

    private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
    private val ADDRESS = Regex("[A-Za-z][A-Za-z0-9+.-]*:[^#\\s]+")
}
