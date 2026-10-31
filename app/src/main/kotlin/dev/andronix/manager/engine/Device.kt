package dev.andronix.manager.engine

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.os.Looper
import android.os.StatFs
import java.io.File

/** The phone: its Android version and ABIs, the apps on it, and pm, used as root. */
object Device {
    val sdk = Build.VERSION.SDK_INT
    val abis: List<String> = Build.SUPPORTED_ABIS.toList()

    /** The F-Droid apps that can take over an app's updates once it is installed, the first one on the phone. */
    private val updaters = listOf("org.fdroid.fdroid", "org.fdroid.basic")

    /**
     * Android's package manager, through a system context made in this process (as root tools do), for the
     * signing keys of apps and APKs. Where that fails it is null: pm still refuses an update signed with another key.
     */
    private val packageManager: PackageManager? by lazy {
        try {
            if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            (activityThread.getMethod("getSystemContext").invoke(thread) as Context).packageManager
        } catch (e: Throwable) {
            Out.line("(signing keys are left to pm: no package manager here, ${e.javaClass.simpleName})")
            null
        }
    }

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
    fun versionCode(id: String): Long? = pm("list", "packages", "--show-versioncode", id).second
        .lineSequence().firstOrNull { it.startsWith("package:$id ") }
        ?.substringAfter("versionCode:")?.trim()?.toLongOrNull()

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
    fun uninstallKeepingData(id: String): Boolean = pm("uninstall", "-k", "--user", "current", id).let { (ok, said) -> ok && said.contains("Success") }

    private fun pm(vararg args: String): Pair<Boolean, String> {
        val process = ProcessBuilder(listOf("pm") + args).redirectErrorStream(true).start()
        process.outputStream.close()
        val said = process.inputStream.bufferedReader().readText()
        return (process.waitFor() == 0) to said
    }
}
