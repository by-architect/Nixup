package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * An app's data for the main user: where each part of it is, how Android made its folders (owner, group, mode, SELinux
 * label), files put into one as the app's own, as if it had made them, and the runtime permissions it was granted. A
 * restore from backups (see Backup) and a reinstall that keeps the data (see Reinstall) both put an app's data back so.
 */
object AppData {
    /** The parts that are folders of the phone: its own two, and its folders in Android/ of the shared storage. */
    val FOLDERS = listOf("data", "deviceData", "storage", "media", "obb")

    /** The parts that are the app's own folders, which Android makes at its install (the others: on the shared storage). */
    val OWN = listOf("data", "deviceData")

    /** The cache folders Android makes in an app's own folders, left out of backups. */
    val CACHES = listOf("cache", "code_cache")

    /** The folder of one of an app's [FOLDERS] parts. */
    fun path(part: String, id: String): String = when (part) {
        "data" -> "/data/user/0/$id"
        "deviceData" -> "/data/user_de/0/$id"
        "storage" -> "/data/media/0/Android/data/$id"
        "media" -> "/data/media/0/Android/media/$id"
        "obb" -> "/data/media/0/Android/obb/$id"
        else -> throw IllegalArgumentException(part)
    }

    /** A folder as the phone names it: /data/media/0/DCIM is /storage/emulated/0/DCIM, and an SD card's /storage/<card>/... */
    fun shown(path: String): String {
        Regex("^/data/media/(\\d+)(/.*)?$").find(path)?.let { return "/storage/emulated/${it.groupValues[1]}${it.groupValues[2]}" }
        Regex("^/mnt/media_rw/([^/]+)(/.*)?$").find(path)?.let { return "/storage/${it.groupValues[1]}${it.groupValues[2]}" }
        return path
    }

    /**
     * The link an older Android puts in an app's data to its native libraries (lib, to its folder in /data/app): left
     * out of backups and never replaced by a restore, as Neo Backup does, as it leads to where the app was installed then.
     */
    fun libLink(folder: File) = Files.isSymbolicLink(File(folder, "lib").toPath())

    /** The app's runtime permissions as they are now: granted or not, and whether the user set or fixed them. */
    fun permissions(id: String): JSONObject = JSONObject().apply {
        Device.dump(id)?.runtime?.forEach { (name, p) ->
            put(name, JSONObject().put("granted", p.granted).put("userSet", p.userSet).put("userFixed", p.userFixed))
        }
    }

    /**
     * Grants [id] the runtime permissions it had ([saved], as [permissions] has them) that it asks for and has not now,
     * with the user's flags; none is taken away. Returns how many were granted.
     */
    fun grant(id: String, saved: JSONObject): Int {
        val dump = Device.dump(id) ?: return 0
        var granted = 0
        for (name in saved.keys()) {
            val was = saved.optJSONObject(name) ?: continue
            if (!was.optBoolean("granted") || dump.runtime[name]?.granted != false) continue
            if (Device.setPermission(id, name, true, was.optBoolean("userSet"), was.optBoolean("userFixed"))) granted++
        }
        return granted
    }

    /** How a part's folder is to be: whether it was there, and the group, mode and SELinux label it gets. */
    class Made(val existed: Boolean, val gid: Int?, val mode: String, val label: String, val own: Boolean)

    /**
     * Its own folders (data, deviceData) as Android made them at the install: their group, mode and label; a folder of
     * the shared storage as the one above it has them (Android/data: ext_data_rw, Android/obb: ext_obb_rw, Android/media),
     * with its uid, as apps get them there.
     */
    fun madeAs(part: String, path: String): Made {
        val folder = File(path)
        val own = part in OWN
        val like = if (own || folder.isDirectory) folder else folder.parentFile!!
        val existed = folder.isDirectory
        if (!like.isDirectory) return Made(existed, null, "2770", "", own)
        val stat = Os.stat(like.path)
        val mode = if (existed) Integer.toOctalString(stat.st_mode and "7777".toInt(8)) else "2770"
        return Made(existed, stat.st_gid, mode, Device.run("stat", "-c", "%C", like.path).second.trim(), own)
    }

    /**
     * Makes the files put in [path] the app's ([uid]), with the folder's group, mode and label as Android made it
     * ([made]); its own folders' caches (left out of backups, made by the install) stay as they are.
     */
    fun handOver(path: String, uid: Int, made: Made) {
        val folder = File(path)
        if (!folder.isDirectory) return
        val gid = made.gid ?: uid
        val label = made.label.takeIf { it.startsWith("u:") }
        Device.run("chown", "$uid:$gid", path)
        Device.run("chmod", made.mode, path)
        label?.let { Device.run("chcon", it, path) }
        for (entry in folder.listFiles().orEmpty().filter { !made.own || it.name !in CACHES }) {
            Device.run("chown", "-R", "$uid:$gid", entry.path)
            // -h: a link is labelled itself, never what it points to (Termux's storage/shared is the whole shared storage).
            label?.let { Device.run("chcon", "-R", "-h", it, entry.path) }
        }
    }

    /**
     * Moves [from] to [to] (which is not there): a rename where it can be; else a copy that keeps links, special files
     * (a FIFO), owners, modes and times, and the original goes. A folder encrypted with another key refuses a rename
     * into it (Android's device-encrypted /data/user_de, from Nixup's own folder: EPERM or EXDEV, as the kernel has it).
     */
    fun move(from: File, to: File): Boolean {
        if (runCatching { Os.rename(from.path, to.path) }.isSuccess) return true
        if (Device.run("cp", "-a", from.path, to.path).first != 0) {
            deleteTree(to)
            return false
        }
        deleteTree(from)
        return true
    }

    /**
     * Waits (up to 30 s, as Neo Backup does) for Android to have made a newly installed app's folders after pm said
     * Success, so its data does not go in while Android is still setting it up.
     */
    fun settled(id: String): Boolean {
        repeat(30) {
            if (Device.uid(id) != null && File(path("data", id)).isDirectory && File(path("deviceData", id)).isDirectory) return true
            Thread.sleep(1000)
        }
        return false
    }
}
