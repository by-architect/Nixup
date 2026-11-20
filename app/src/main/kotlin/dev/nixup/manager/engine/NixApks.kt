package dev.nixup.manager.engine

import org.json.JSONObject
import java.io.File

/**
 * Apps whose APK is a Nix package: android.packages.nix, each with its APK in Nixup's store (the profile's nixApks),
 * which the build fetched or built before the engine runs, as it does the config's other Nix packages. Nix checked
 * what it is (a fetchurl's hash), so nothing is downloaded or looked up here: the APK is read where it is, and
 * installed when it is newer than the app on the phone. The config names one APK, so a new one in the config is the
 * update, and one older than the app installed (a rollback) is left, as Android installs no older version over a newer.
 */
object NixApks {
    const val NAME = "nix"

    /** The config's apps from Nix: package name to the APK's store path. */
    fun apps(android: JSONObject): Map<String, String> {
        val apks = android.optJSONObject("nixApks") ?: return emptyMap()
        return apks.keys().asSequence().associateWith { apks.getString(it) }
    }

    /** Whether the APK at [path] is in Nixup's store yet: a dry run fetches no Nix packages. */
    fun fetched(data: File, path: String): Boolean = Etc.outside(data, path).exists()

    /** What a build does for app [id], whose APK is [path] in the store: install or update it, nothing, or a problem. */
    fun plan(data: File, id: String, path: String, installed: Device.Installed?): Releases.Plan {
        val file = apk(Etc.outside(data, path))
            ?: return Releases.Plan.Problem("$id: $path is not an APK, nor a folder with exactly one (is it in Nixup's store?)")
        val apk = Device.archive(file) ?: return Releases.Plan.Problem("$id: $path cannot be read as an APK")
        if (apk.id != id) return Releases.Plan.Problem("$id: the APK at $path is ${apk.id}: fix the id or the apk in the config")
        if (installed != null && apk.versionCode <= installed.versionCode) {
            if (apk.versionCode < installed.versionCode) {
                Out.line("$NAME: $id: its APK is ${apk.versionName} (code ${apk.versionCode}), older than the one installed (${installed.versionName}, code ${installed.versionCode}): Android installs no older version, so it stays")
            }
            return Releases.Plan.Current(installed.versionName)
        }
        return Releases.Plan.Install(
            Installer.Step(
                repo = NAME, id = id, name = Device.label(id) ?: id, version = apk.versionName, code = apk.versionCode,
                size = file.length(), url = path, sha256 = null, listedBy = "Nixup's store",
                keys = installed?.signers?.takeIf { it.isNotEmpty() },
                keysFrom = "of the app installed: Android takes an update only with the same key, so uninstall it first, or take it out of the config",
                installed = installed, installers = emptyList(), local = file,
            ),
        )
    }

    /** The APK at [path]: the file itself, or the only one in the folder. */
    private fun apk(path: File): File? = when {
        path.isFile -> path
        path.isDirectory -> path.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }?.singleOrNull()
        else -> null
    }
}
