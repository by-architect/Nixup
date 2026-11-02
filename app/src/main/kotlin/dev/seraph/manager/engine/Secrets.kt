package dev.seraph.manager.engine

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The config's secrets (android.secrets): files that programs of the config read on the phone, kept in
 * /data/adb/seraph/secrets, which root alone can read. The profile names them, never with what is in them. A build on a
 * PC (seraph-rebuild) sends them along, in [incoming]; a build on the phone finds them there already. The ones Seraph put
 * there are listed in .managed, so one that leaves the config goes, while the files put there by hand (nix-access-tokens)
 * stay. Like the settings, they are planned and checked first ([plan]), and changed after ([apply]), before the
 * services that read them start.
 */
class Secrets(data: File, private val incoming: File?) {
    private val dir = File(data, "secrets")
    private val managedList = File(dir, ".managed")
    private var wanted: List<String> = emptyList()
    private var sending: List<String> = emptyList()
    private var leaving: List<String> = emptyList()

    /** Whether the plan changes anything. */
    val changes get() = sending.isNotEmpty() || leaving.isNotEmpty()

    fun plan(names: JSONArray?, problems: MutableList<String>) {
        wanted = names?.let { list -> (0 until list.length()).map { list.getString(it) } } ?: emptyList()
        val missing = wanted.filter { sent(it) == null && !File(dir, it).isFile }
        missing.forEach {
            problems += "android.secrets.$it: it is not on the phone (${File(dir, it)}): seraph-rebuild sends it from where the " +
                "config is built (its file), or it is put there by hand"
        }
        // A secret sent again as it was changes nothing.
        sending = wanted.filter { name -> sent(name)?.let { !same(it, File(dir, name)) } == true }
        leaving = managed().filter { it !in wanted && File(dir, it).exists() }
        if (wanted.isEmpty() && leaving.isEmpty()) return
        val parts = wanted.map { if (it in sending) "$it (${if (File(dir, it).isFile) "changed" else "new"})" else it } +
            leaving.map { "$it (to remove: it left the config)" }
        Out.line("Secrets: ${parts.joinToString(", ")}")
    }

    /** Puts the secrets sent with the build in place (readable by root alone), and removes those that left the config. */
    fun apply(): Int {
        if (!changes) return 0
        var failed = 0
        dir.mkdirs()
        Os.chmod(dir.path, "700".toInt(8))
        for (name in sending) {
            try {
                val target = File(dir, name)
                val partial = File(dir, ".$name.new")
                sent(name)!!.copyTo(partial, overwrite = true)
                Os.chown(partial.path, 0, 0)
                Os.chmod(partial.path, "400".toInt(8))
                if (!partial.renameTo(target)) throw IllegalStateException("could not put it in $target")
                Out.line("✔ Secret $name: in place")
            } catch (e: Exception) {
                Out.line("✘ Secret $name: ${e.message}")
                failed++
            }
        }
        for (name in leaving) {
            if (File(dir, name).delete()) Out.line("✔ Secret $name: removed (it left the config)") else failed++
        }
        // Seraph's own: the config's secrets that it put there, now or before.
        val mine = (managed() + sending).filter { it in wanted }.distinct().sorted()
        managedList.writeText(mine.joinToString("") { "$it\n" })
        return failed
    }

    /** The secrets Seraph put there (the module's removal takes them all, with its data). */
    private fun managed(): List<String> = if (managedList.isFile) managedList.readLines().filter { it.isNotBlank() } else emptyList()

    private fun sent(name: String): File? = incoming?.let { File(it, name) }?.takeIf { it.isFile }

    private fun same(a: File, b: File) = b.isFile && a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())

    companion object {
        /**
         * The secrets a build on the phone puts in place itself, as its config gives them ([json]: each one's file, in the
         * config's folder or a flake's copy of it in Seraph's [store], or its text), into [into], readable by root alone,
         * for [Secrets] to take as if a PC had sent them. 0, or 2 when one cannot be had.
         */
        fun stage(json: File, into: File, store: File?): Int {
            val secrets = JSONObject(json.readText())
            into.mkdirs()
            Os.chmod(into.path, "700".toInt(8))
            var failed = 0
            for (name in secrets.keys()) {
                val secret = secrets.getJSONObject(name)
                val target = File(into, name)
                try {
                    when {
                        !secret.isNull("text") -> target.writeText(secret.getString("text"))
                        !secret.isNull("file") -> {
                            val path = secret.getString("file")
                            val source = if (store != null && path.startsWith("/nix/store/")) File(store, path.removePrefix("/")) else File(path)
                            if (!source.isFile) throw IllegalStateException("there is no $path to put on the phone")
                            source.copyTo(target, overwrite = true)
                        }
                        else -> continue
                    }
                    Os.chmod(target.path, "600".toInt(8))
                } catch (e: Exception) {
                    Out.line("✘ $name: ${e.message}")
                    failed++
                }
            }
            return if (failed == 0) 0 else 2
        }
    }
}
