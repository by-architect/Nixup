package dev.nixup.manager.engine

import org.json.JSONObject
import java.io.File

/**
 * The config's scripts, as NixOS runs them (a profile's activation part, from nix/activation.nix):
 *  - system.preSwitchChecks before a build changes anything (a dry run's too), with the profile and the action
 *    (switch, dry-activate) as $1 and $2: one that fails stops the build ([check]); NIXOS_NO_CHECK=1 leaves them out;
 *  - system.activationScripts with each build, after the users, /etc and the folders of systemd.tmpfiles are in place
 *    and before the services start ([apply]), and at each start of the phone, before the services start ([boot]);
 *    with a dry run only those that support it, with NIXOS_ACTION=dry-activate;
 *  - Nixup's own undo of a script, once when the script leaves the config ([apply]) and when the module goes
 *    ([undoAll]). The undos of the last build are kept in [undoDir] (one file each), with the bash they run with.
 * Each runs as root in Nixup's view (see Units), with what it says shown. (system.userActivationScripts are a unit of
 * the users' own: see nix/activation.nix.)
 */
class Activation(private val data: File) {
    private val dir = File(data, "activation")
    private val undoDir = File(dir, "undo")
    private val bashFile = File(dir, "bash")

    /** The pre-switch checks of [android] (the profile at [profile]), when it has some: a problem when one failed. */
    fun check(android: JSONObject, profile: File, dryRun: Boolean, problems: MutableList<String>) {
        val activation = android.optJSONObject("activation") ?: return
        val checks = activation.string("preSwitchChecks") ?: return
        if (System.getenv("NIXOS_NO_CHECK") == "1") {
            Out.line("Pre-switch checks: left out (NIXOS_NO_CHECK=1)")
            return
        }
        Out.line("Pre-switch checks (system.preSwitchChecks):")
        val status = run(activation.getString("bash"), "checks", checks,
            listOf(profile.absolutePath, if (dryRun) "dry-activate" else "switch"))
        if (status == 0) Out.line("✔ Pre-switch checks passed")
        else problems += "system.preSwitchChecks: a check failed (it says which above)"
    }

    /**
     * With a build: the undo of each script that left the config, then the activation script, and the undos of
     * [android] kept for later. With [dryRun], what it would undo, and the scripts that support a dry run. The number of
     * scripts that failed.
     */
    fun apply(android: JSONObject, dryRun: Boolean): Int {
        val activation = android.optJSONObject("activation")
        // The scripts that left the config (not one that is still there without an undo: its old undo is forgotten).
        val staying = activation?.strings("names").orEmpty().toSet()
        val leaving = keptUndos().filter { it !in staying }
        var failed = 0
        if (dryRun) {
            if (leaving.isNotEmpty()) Out.line("Activation scripts that left the config, to undo: ${leaving.joinToString(", ")}")
            val dry = activation?.string("dryScript")
            if (activation == null || dry == null) return 0
            Out.line("Activation scripts (dry run, those that support one):")
            if (run(activation.getString("bash"), "dry-activate", dry, emptyList(), mapOf("NIXOS_ACTION" to "dry-activate")) != 0) failed++
            return failed
        }
        // What left goes first, with the bash it was kept with.
        for (name in leaving) failed += undo(name)
        val script = activation?.string("script")
        if (activation != null && script != null) {
            Out.line("Activation scripts (system.activationScripts):")
            if (run(activation.getString("bash"), "activate", script, emptyList(), mapOf("NIXOS_ACTION" to "switch")) == 0) {
                Out.line("✔ Activation scripts ran")
            } else {
                Out.line("✘ An activation script failed (it says which above); the others ran")
                failed++
            }
        }
        keep(activation)
        return failed
    }

    /** At boot: the activation script of the profile in place ([android]), as NixOS runs it at each start. */
    fun boot(android: JSONObject?): Int {
        val activation = android?.optJSONObject("activation") ?: return 0
        val script = activation.string("script") ?: return 0
        val status = run(activation.getString("bash"), "activate", script, emptyList())
        if (status != 0) {
            Out.line("✘ An activation script failed at boot (it says which above)")
            Notify.event("build", "warn", "An activation script failed at boot", "Its output is in the Logs tab", quiet = true)
        }
        return if (status == 0) 0 else 1
    }

    /** The module is going: every script's undo that the last build kept. */
    fun undoAll(): Int = keptUndos().sumOf { undo(it) }

    /** Whether [android] (a profile's android part) has an activation script to run at boot. */
    fun hasScript(android: JSONObject?): Boolean = android?.optJSONObject("activation")?.string("script") != null

    private fun keptUndos(): List<String> = undoDir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    /** Runs the undo of [name], kept by an earlier build, and forgets it; one that fails is kept, to be tried again. */
    private fun undo(name: String): Int {
        val file = File(undoDir, name)
        val bash = runCatching { bashFile.readText().trim() }.getOrDefault("")
        if (bash.isEmpty()) {
            Out.line("✘ $name: its undo has no bash to run with; it is kept")
            return 1
        }
        Out.line("Undo of activation script $name (it left the config):")
        return if (run(bash, "undo", file.readText(), emptyList()) == 0) {
            file.delete()
            Out.line("✔ $name: undone")
            0
        } else {
            Out.line("✘ $name: its undo failed (said above); it is tried again with the next build")
            1
        }
    }

    /**
     * The undos of [activation] (a profile's), for when their scripts leave the config or the module goes; those of
     * scripts still there without one now are forgotten (an undo that failed for a script that left stays, see [undo]).
     */
    private fun keep(activation: JSONObject?) {
        val undos = activation?.optJSONObject("undo")
        val names = undos?.keys()?.asSequence()?.toList().orEmpty()
        // A name from the config's attribute names is a file name only when it is a plain one.
        val safe = names.filter { it.matches(Regex("[A-Za-z0-9._-]+")) && it != "." && it != ".." }
        names.filterNot { it in safe }.forEach { Out.line("✘ $it: its undo is not kept (only an activation script named with letters, digits and . _ - can have one)") }
        val staying = activation?.strings("names").orEmpty().toSet()
        keptUndos().filter { it in staying && it !in safe }.forEach { File(undoDir, it).delete() }
        if (safe.isEmpty()) {
            // Nothing kept (any that failed stays, with its bash): nothing of Nixup's left behind.
            if (keptUndos().isEmpty()) {
                bashFile.delete()
                undoDir.delete()
                dir.delete()
            }
            return
        }
        undoDir.mkdirs()
        bashFile.writeText(activation!!.getString("bash") + "\n")
        for (name in safe) File(undoDir, name).writeText(undos!!.getString(name))
    }

    /**
     * Runs [text] with [bash] in Nixup's view, as root, with [args] and [env] (and a clean environment, as the services
     * have), what it says shown as it says it; its exit status.
     */
    private fun run(bash: String, name: String, text: String, args: List<String>, env: Map<String, String> = emptyMap()): Int {
        val view = File(data, "units/view.sh")
        if (!view.isFile) Units(data).writeView()
        // (In Nixup's tmp: [dir] holds only what a build keeps.)
        val tmp = File(data, "tmp").apply { mkdirs() }
        val script = File(tmp, "activation-$name.sh").apply { writeText(text) }
        // The view starts /system/bin/sh with a file: it starts bash with the script.
        val start = File(tmp, "activation-$name.start").apply {
            writeText("exec ${quote(bash)} ${quote(script.path)}" + args.joinToString("") { " " + quote(it) } + "\n")
        }
        return runCatching {
            val process = ProcessBuilder(Units.busybox(), "sh", view.path, start.path)
                .redirectErrorStream(true).redirectInput(File("/dev/null"))
                .apply { Units.cleanEnvironment(environment()); environment().putAll(env) }
                .start()
            process.inputStream.bufferedReader().forEachLine { Out.line("  $it") }
            process.waitFor()
        }.getOrElse {
            Out.line("✘ $name: could not run (${it.message})")
            1
        }
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
}
