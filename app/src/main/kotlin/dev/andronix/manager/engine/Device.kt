package dev.andronix.manager.engine

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.os.Looper
import android.os.StatFs
import java.io.File

/**
 * The phone: its Android version and ABIs, the apps on it and their settings, through Android's own commands
 * (pm, appops, cmd, settings, dumpsys) used as root. App settings are those of the main user (0).
 */
object Device {
    val sdk = Build.VERSION.SDK_INT
    val abis: List<String> = Build.SUPPORTED_ABIS.toList()

    /** The F-Droid apps that can take over an app's updates once it is installed, the first one on the phone. */
    private val updaters = listOf("org.fdroid.fdroid", "org.fdroid.basic")

    /**
     * A system context made in this process, as root tools do: for the signing keys of apps and APKs, and the
     * network policy. Where that fails it is null: pm still refuses an update signed with another key.
     */
    private val context: Context? by lazy {
        try {
            if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(thread) as Context
        } catch (e: Throwable) {
            Out.line("(no system context here: ${e.javaClass.simpleName}; signing keys are left to pm)")
            null
        }
    }

    private val packageManager: PackageManager? get() = context?.packageManager

    class Installed(val versionCode: Long, val versionName: String, val signers: Set<String>)

    fun installed(id: String): Installed? {
        packageManager?.let { pm ->
            return try {
                val info = pm.getPackageInfo(id, PackageManager.GET_SIGNING_CERTIFICATES)
                Installed(info.longVersionCode, info.versionName ?: "", signers(info.signingInfo))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        val code = versionCode(id) ?: return null
        return Installed(code, code.toString(), emptySet())
    }

    /** The installed version, asked of pm itself (no cache in between), or null when the app is not installed. */
    fun versionCode(id: String): Long? = run("pm", "list", "packages", "--show-versioncode", id).second
        .lineSequence().firstOrNull { it.startsWith("package:$id ") }
        ?.substringAfter("versionCode:")?.trim()?.toLongOrNull()

    /** The app's user id (its uid for the main user), or null when it is not installed. */
    fun uid(id: String): Int? = run("pm", "list", "packages", "-U", id).second
        .lineSequence().firstOrNull { it.startsWith("package:$id ") }
        ?.substringAfter("uid:")?.trim()?.substringBefore(',')?.toIntOrNull()

    /** The SHA-256 of the certificates an APK is signed with, or null when they cannot be read here. */
    fun signers(apk: File): Set<String>? {
        val pm = packageManager ?: return null
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return emptySet()
        return signers(info.signingInfo)
    }

    /** The current signers and, after a key rotation, the earlier ones: F-Droid's index may list either. */
    private fun signers(info: SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        val all = mutableListOf<Signature>()
        info.apkContentsSigners?.let { all += it }
        if (!info.hasMultipleSigners()) info.signingCertificateHistory?.let { all += it }
        return all.map { Net.sha256(it.toByteArray()) }.toSet()
    }

    fun updater(): String? = updaters.firstOrNull { versionCode(it) != null }

    fun freeBytes(dir: File): Long = StatFs(dir.path).availableBytes

    /** Installs (or updates) an APK, streamed into pm; [installer] becomes its installer of record. Null when it worked, else what pm said. */
    fun install(apk: File, installer: String?): String? {
        val command = mutableListOf("pm", "install", "-r", "--user", "current", "-S", apk.length().toString())
        if (installer != null) command += listOf("-i", installer)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        apk.inputStream().use { input -> process.outputStream.use { input.copyTo(it) } }
        val said = process.inputStream.bufferedReader().readText().trim()
        return if (process.waitFor() == 0 && said.contains("Success")) null else said.ifEmpty { "exit ${process.exitValue()}" }
    }

    /** Uninstalls an app but keeps its data, so installing it again brings the data back. */
    fun uninstallKeepingData(id: String): Boolean =
        run("pm", "uninstall", "-k", "--user", "current", id).let { (status, said) -> status == 0 && said.contains("Success") }

    // --- An app's permissions, state and settings.

    class Permission(val granted: Boolean, val userSet: Boolean, val userFixed: Boolean)

    /** What dumpsys knows of an installed app: the permissions it asks for, and for the main user its runtime permissions, enabled state and whether it is suspended. Null when it is not installed for the main user. */
    class Dump(val requested: Set<String>, val runtime: Map<String, Permission>, val enabled: Int, val suspended: Boolean)

    private val permissionLine = Regex("""([\w.]+): granted=(true|false)(?:, flags=\[ ?(.*?) ?])?.*""")

    fun dump(id: String): Dump? {
        val said = run("dumpsys", "package", id).second
        if (!said.contains("Package [$id]")) return null
        val requested = HashSet<String>()
        val runtime = HashMap<String, Permission>()
        var enabled = 0
        var suspended = false
        var installed = false
        var user = -1
        var section: String? = null
        var sectionIndent = 0
        var inPackages = false
        for (raw in said.lineSequence()) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (!inPackages) {
                inPackages = text == "Packages:"
                continue
            }
            if (text.startsWith("Hidden system packages:")) break
            val indent = raw.length - raw.trimStart().length
            if (section != null && indent <= sectionIndent) section = null
            when (section) {
                "requested" -> requested += text.substringBefore(':')
                "runtime" -> permissionLine.matchEntire(text)?.let { m ->
                    val flags = m.groupValues[3].split('|')
                    runtime[m.groupValues[1]] = Permission(m.groupValues[2] == "true", "USER_SET" in flags, "USER_FIXED" in flags)
                }
                else -> when {
                    text == "requested permissions:" -> { section = "requested"; sectionIndent = indent }
                    text == "runtime permissions:" && user == 0 -> { section = "runtime"; sectionIndent = indent }
                    text.startsWith("User ") -> {
                        user = text.removePrefix("User ").substringBefore(':').toIntOrNull() ?: -1
                        if (user == 0) {
                            installed = Regex("""\binstalled=(true|false)""").find(text)?.groupValues?.get(1) == "true"
                            enabled = Regex("""\benabled=(\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                            suspended = Regex("""\bsuspended=(true|false)""").find(text)?.groupValues?.get(1) == "true"
                        }
                    }
                }
            }
        }
        // Uninstalled with its data kept, an app is still listed: not installed for the main user.
        return if (installed) Dump(requested, runtime, enabled, suspended) else null
    }

    /** Grants or revokes a runtime permission, with the user-set and user-fixed flags of the state wanted. */
    fun setPermission(id: String, permission: String, granted: Boolean, userSet: Boolean, userFixed: Boolean): Boolean {
        // A fixed permission first comes loose, so the grant or revoke goes through.
        run("pm", "clear-permission-flags", "--user", "0", id, permission, "user-fixed")
        val ok = run("pm", if (granted) "grant" else "revoke", "--user", "0", id, permission).first == 0
        val set = listOfNotNull("user-set".takeIf { userSet }, "user-fixed".takeIf { userFixed })
        val clear = listOfNotNull("user-set".takeIf { !userSet }, "user-fixed".takeIf { !userFixed })
        if (set.isNotEmpty()) run("pm", "set-permission-flags", "--user", "0", id, permission, *set.toTypedArray())
        if (clear.isNotEmpty()) run("pm", "clear-permission-flags", "--user", "0", id, permission, *clear.toTypedArray())
        return ok
    }

    /** An app-op's mode for the app's uid and for the package ("default" where none is set), or null when this Android has no such op. */
    fun appOp(id: String, op: String): Pair<String, String>? {
        val said = run("appops", "get", id, op).second
        if (said.contains("Unknown operation") || said.contains("Error")) return null
        var uidMode = "default"
        var packageMode = "default"
        for (line in said.lineSequence()) {
            val text = line.trim()
            if (text.startsWith("Uid mode: $op:")) uidMode = mode(text.removePrefix("Uid mode: $op:"))
            else if (text.startsWith("$op:")) packageMode = mode(text.removePrefix("$op:"))
        }
        return uidMode to packageMode
    }

    private fun mode(text: String) = text.trim().substringBefore(';').trim()

    fun setAppOp(id: String, op: String, uidMode: String, packageMode: String): Boolean {
        val uid = run("appops", "set", "--uid", id, op, uidMode).first == 0
        return run("appops", "set", id, op, packageMode).first == 0 && uid
    }

    /** The apps exempt from battery optimization: by the user's choice, and by the system (which cannot be changed). */
    fun batteryExempt(): Pair<Set<String>, Set<String>> {
        val user = HashSet<String>()
        val system = HashSet<String>()
        for (line in run("cmd", "deviceidle", "whitelist").second.lineSequence()) {
            val parts = line.trim().split(',')
            if (parts.size < 2) continue
            when (parts[0]) {
                "user" -> user += parts[1]
                "system" -> system += parts[1]
            }
        }
        return user to system
    }

    fun setBatteryExempt(id: String, exempt: Boolean): Boolean =
        run("cmd", "deviceidle", "whitelist", (if (exempt) "+" else "-") + id).first == 0

    /** Android's network policy, for an app's uid policy bits (background data, Data Saver, LineageOS's network switches). */
    private val networkPolicy: Any? by lazy {
        try {
            context?.getSystemService("netpolicy")
        } catch (e: Throwable) {
            null
        }
    }

    /** A uid policy bit by its name in NetworkPolicyManager, or null when this system does not have it. */
    fun networkFlag(name: String): Int? = try {
        if (networkPolicy == null) null else Class.forName("android.net.NetworkPolicyManager").getField(name).getInt(null)
    } catch (e: Throwable) {
        null
    }

    fun uidPolicy(uid: Int): Int? = try {
        networkPolicy?.let { it.javaClass.getMethod("getUidPolicy", Int::class.javaPrimitiveType).invoke(it, uid) as Int }
    } catch (e: Throwable) {
        null
    }

    fun setUidPolicyFlag(uid: Int, flag: Int, on: Boolean): Boolean = try {
        val policy = networkPolicy ?: return false
        policy.javaClass.getMethod(if (on) "addUidPolicy" else "removeUidPolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(policy, uid, flag)
        true
    } catch (e: Throwable) {
        false
    }

    /** The app's languages as tags ("tr-TR,en-US"), empty when it follows the phone. */
    fun appLanguages(id: String): String = run("cmd", "locale", "get-app-locales", id, "--user", "0").second
        .substringAfter('[', "").substringBefore(']').split(',')
        .map { it.trim().replace('_', '-') }.filter { it.isNotEmpty() }.joinToString(",")

    fun setAppLanguages(id: String, tags: String): Boolean {
        val command = mutableListOf("cmd", "locale", "set-app-locales", id, "--user", "0")
        if (tags.isNotEmpty()) command += listOf("--locales", tags)
        return run(*command.toTypedArray()).first == 0
    }

    /** Whether the app opens the web links it supports, or null when it supports none. */
    fun opensLinks(id: String): Boolean? = Regex("""link handling allowed: (true|false)""", RegexOption.IGNORE_CASE)
        .find(run("pm", "get-app-links", "--user", "0", id).second)?.groupValues?.get(1)?.let { it == "true" }

    fun setOpensLinks(id: String, allowed: Boolean): Boolean =
        run("pm", "set-app-links-allowed", "--user", "0", "--package", id, allowed.toString()).first == 0

    fun roleHolders(role: String): List<String> = run("cmd", "role", "get-role-holders", "--user", "0", role).second
        .split(';', '\n').map { it.trim() }.filter { PACKAGE_NAME.matches(it) }

    /** Makes [id] the role's holder, or leaves the role with none when [id] is null. */
    fun setRoleHolder(role: String, id: String?): Boolean =
        if (id == null) run("cmd", "role", "clear-role-holders", "--user", "0", role).first == 0
        else run("cmd", "role", "add-role-holder", "--user", "0", role, id).first == 0

    /** The app's services for an intent action, as full component names (package/class). */
    fun services(id: String, action: String): List<String> =
        run("cmd", "package", "query-services", "--components", "--user", "0", "-a", action).second
            .lineSequence().map { it.trim() }.filter { it.startsWith("$id/") }.map(::fullComponent).toList()

    fun fullComponent(component: String): String {
        val name = component.substringBefore('/')
        val cls = component.substringAfter('/')
        return if (cls.startsWith(".")) "$name/$name$cls" else component
    }

    fun secureSetting(key: String): String = run("settings", "get", "secure", key).second.trim().let { if (it == "null") "" else it }

    fun putSecureSetting(key: String, value: String): Boolean = run("settings", "put", "secure", key, value).first == 0

    fun setNotificationListener(component: String, allowed: Boolean): Boolean =
        run("cmd", "notification", if (allowed) "allow_listener" else "disallow_listener", component).first == 0

    fun setEnabledState(id: String, state: Int): Boolean {
        val verb = when (state) {
            0 -> "default-state"
            1 -> "enable"
            2 -> "disable"
            3 -> "disable-user"
            else -> "disable-until-used"
        }
        return run("pm", verb, "--user", "0", id).first == 0
    }

    fun setSuspended(id: String, suspended: Boolean): Boolean =
        run("pm", if (suspended) "suspend" else "unsuspend", "--user", "0", id).first == 0

    /** Runs one of Android's commands with no input (it would hand that to Android, which may not read /data/adb); its exit status and what it said. */
    fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val said = process.inputStream.bufferedReader().readText()
        return process.waitFor() to said
    }
}
