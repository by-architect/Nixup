package dev.seraph.manager.engine

import android.system.Os
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.PlayException
import com.aurora.gplayapi.android.NativeDeviceProperties
import com.aurora.gplayapi.device.DeviceProfile
import com.aurora.gplayapi.device.DeviceRegistry
import com.aurora.gplayapi.engine.OkHttpEngine
import com.aurora.gplayapi.model.AppFile
import com.aurora.gplayapi.session.AnonymousSession
import com.aurora.gplayapi.session.AuthenticatedSession
import com.aurora.gplayapi.session.DefaultPlaySessions
import com.aurora.gplayapi.session.PlaySession
import com.aurora.gplayapi.session.PlaySessions
import com.aurora.gplayapi.session.SessionFactory
import com.aurora.gplayapi.session.TokenType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Apps from Google Play (android.packages.playstore), downloaded as Aurora Store downloads them, with Aurora's gplayapi.
 * The phone checks in with Google Play as itself (its Android, processor, screen, features and languages), so Play
 * gives it the split APKs made for it; it signs in with the config's account (android.playstore: its tokenFile, or the
 * account signed in on the phone) or the one signed in on the phone (the app's Google Play account, [login]), and with
 * none it uses Play's anonymous lane, as a phone signed in to no account, which gets the free apps alone. The session is
 * kept in repos/playstore/session.json (without the account's token), so a run checks in again only when it ran out.
 *
 * What Play has of each app (its version, size, signing keys, the libraries it needs) is asked for in one request and
 * kept in repos/playstore/apps.json: a build asks again once it is a day old, an update check (and Upgrade) always.
 * Where an app's files are is asked only when it is downloaded, since those addresses expire.
 *
 * Play signs no index as an F-Droid repository does: an app is trusted for coming from Google over https, its files for
 * matching the SHA-256 Play lists, and its signing key for being one Play lists for it (Android then holds updates to
 * it), as Aurora Store trusts them.
 */
class PlayStore(private val data: File, android: JSONObject?) {
    private val dir = File(data, "repos/$NAME")
    private val appsFile = File(dir, "apps.json")
    private val sessionFile = File(dir, "session.json")

    /** The config's account (android.playstore.account) and its token's file on the phone (tokenFile), when it gives them. */
    private val account: String?
    private val tokenFile: File?

    init {
        val config = android?.optJSONObject(NAME)
        account = config?.text("account")
        tokenFile = config?.text("tokenFile")?.let { File(it) }
    }

    private class Credential(val email: String, val token: String)

    /** Who asks Google Play: the account and its token, or null for none (the anonymous lane). Read once a run. */
    private val credential: Credential? by lazy { credential() }

    private fun credential(): Credential? {
        tokenFile?.let { file ->
            val token = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: throw Stop("android.playstore.tokenFile: there is no token in $file")
            val email = account ?: throw Stop("android.playstore.tokenFile: the config names no account for it (android.playstore.account)")
            return Credential(email, token)
        }
        val signedIn = signedIn(data)
        if (account != null) {
            if (signedIn == null) {
                throw Stop("android.playstore.account: $account is not signed in on this phone: sign in in the Seraph app (⋮ → Google Play account), or give its tokenFile")
            }
            if (!signedIn.email.equals(account, ignoreCase = true)) {
                throw Stop("android.playstore.account is $account, but the phone is signed in to Google Play as ${signedIn.email}: sign in as $account (⋮ → Google Play account), or give its tokenFile")
            }
        }
        return signedIn
    }

    // --- The session with Google Play.

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private val engine by lazy { OkHttpEngine(http) }
    private val client by lazy { PlayClient(engine, PlaySessions { session() }) }
    private val lock = Mutex()
    private var current: PlaySession? = null

    /**
     * The phone as Google Play is told it is: read from the phone itself (as Aurora's "native" device), so the split
     * APKs it gets are its own; where that cannot be read here, a phone Aurora knows, with the same processor.
     */
    private val device: DeviceProfile by lazy {
        try {
            val context = Device.systemContext ?: throw IllegalStateException("no system context here")
            DeviceRegistry.parse("native", NativeDeviceProperties.read(context))
        } catch (e: Throwable) {
            DeviceRegistry.default.also {
                Out.line("Google Play: this phone's own description could not be read (${e.message ?: e.javaClass.simpleName}), so it checks in as a ${it.name}")
            }
        }
    }

    /** The phone's languages: Play's text comes in the first, and the language splits of apps in all of them. */
    private val languages: List<Locale> by lazy {
        Device.languages().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { Locale.forLanguageTag(it) }
            .filter { it.language.isNotEmpty() }.distinct().ifEmpty { listOf(Locale.getDefault()) }
    }

    private suspend fun session(): PlaySession = lock.withLock { current ?: open().also { current = it } }

    /** The session kept from before, when it is still good for who asks now; else a new one, kept. */
    private suspend fun open(): PlaySession {
        val who = credential
        val locale = languages.first()
        val more = languages.drop(1)
        val kept = runCatching { JSONObject(sessionFile.readText()) }.getOrNull()
        val stored = kept?.let { runCatching { JSON.decodeFromString(PlaySession.serializer(), it.getString("session")) }.getOrNull() }
            ?.takeIf { it.device.fingerprint == device.fingerprint && it.locale == locale }
        if (who == null) {
            // Play's own anonymous sessions last eight hours.
            if (stored is AnonymousSession && System.currentTimeMillis() - stored.mintedAtMillis < ANONYMOUS_AGE) return stored.copy(additionalLocales = more)
            val minted = DefaultPlaySessions(engine, device, locale, more).session()
            save(minted, null)
            Out.line("Google Play: checked in with no account (as a phone signed in to none, it gets the free apps alone)")
            return minted
        }
        if (stored is AuthenticatedSession && stored.email.equals(who.email, ignoreCase = true) && kept?.optString("token") == sha256(who.token) &&
            SessionFactory(engine).validate(stored)
        ) {
            return stored.copy(additionalLocales = more)
        }
        return signIn(who.email, who.token)
    }

    /** A new session of the account, from its token (a checkin, the phone's description sent, the token traded for one of Google Play's), kept. */
    private suspend fun signIn(email: String, token: String): AuthenticatedSession {
        val minted = try {
            SessionFactory(engine).authenticate(email, token, TokenType.AAS, device, languages.first(), languages.drop(1))
        } catch (e: PlayException.Unauthorized) {
            throw Stop("Google refused the token of $email (${e.message}): sign in again (⋮ → Google Play account)")
        }
        save(minted, token)
        Out.line("Google Play: signed in as ${minted.email}")
        return minted
    }

    /** Keeps the session, readable by root alone; an account's without its token (a digest of it tells whether it is still the same). */
    private fun save(session: PlaySession, token: String?) {
        val kept = if (session is AuthenticatedSession) session.copy(aasToken = null) else session
        val json = JSONObject().put("session", JSON.encodeToString(PlaySession.serializer(), kept))
        token?.let { json.put("token", sha256(it)) }
        writePrivate(sessionFile, json.toString())
    }

    /** Runs a call to Google Play; when it refuses the session (it ran out, or the account signed out elsewhere), once more with a new one. */
    private fun <T> call(what: String, ask: suspend () -> T): T = runBlocking {
        try {
            try {
                ask()
            } catch (e: PlayException.Unauthorized) {
                lock.withLock { current = null }
                sessionFile.delete()
                ask()
            }
        } catch (e: PlayException) {
            throw IOException(said(what, e), e)
        }
    }

    /** The account that asks, for messages (null: none, or none that can). */
    private val asking: String? get() = runCatching { credential?.email }.getOrNull()

    /** What Google Play's refusal means, in words. */
    private fun said(what: String, e: PlayException): String = when (e) {
        is PlayException.AppNotPurchased -> "$what: Google Play gives it only to an account that bought it (or not in this country)" +
            if (asking == null) ": sign in with one (⋮ → Google Play account)" else ""
        is PlayException.AppNotSupported -> "$what: Google Play does not give it to this phone, or not in this country"
        is PlayException.AppRemoved -> "$what: it is no longer on Google Play"
        is PlayException.NotFound -> "$what: Google Play has nothing under this name"
        is PlayException.NoFilesAvailable -> "$what: Google Play names no files for it"
        is PlayException.RateLimited -> "Google Play asks to wait (too many requests): try again later"
        is PlayException.ServerError -> "Google Play's servers failed (HTTP ${e.code}): try again later"
        is PlayException.Unauthorized -> "Google Play refused ${asking ?: "the phone"} (${e.message})" +
            if (asking != null) ": sign in again (⋮ → Google Play account)" else ""
        is PlayException.RequiresAccount -> "$what: Google Play does this only for an account: sign in with one (⋮ → Google Play account)"
        else -> "$what: Google Play answered ${e.message}"
    }

    // --- What Google Play has of the config's apps.

    /** One of the config's apps as Google Play has it, from apps.json. */
    private class Info(
        val id: String, val name: String, val versionCode: Long, val versionName: String, val offerType: Int, val size: Long,
        val restriction: String, val obb: Boolean, val signers: List<String>, val libraries: List<Library>, val checked: Long,
    )

    private class Library(val id: String, val versionCode: Long, val size: Long)

    /** apps.json: each app's Info, as the account it was asked by ("" for none) was told; another account's do not count. */
    private fun saved(who: String): JSONObject {
        val json = runCatching { JSONObject(appsFile.readText()) }.getOrNull() ?: return JSONObject()
        return json.optJSONObject("apps")?.takeIf { json.optString("as") == who } ?: JSONObject()
    }

    /** Brings what is here of the apps up to date: those not asked for in a day (or all, [force]d), in one request. */
    fun refresh(ids: List<String>, force: Boolean) {
        if (ids.isEmpty()) return
        val who = credential?.email ?: ""
        val apps = saved(who)
        val now = System.currentTimeMillis()
        val stale = ids.filter { id -> force || apps.optJSONObject(id)?.let { now - it.getLong("checked") >= FDroid.MAX_AGE } ?: true }
        if (stale.isEmpty()) {
            val oldest = ids.minOf { apps.getJSONObject(it).getLong("checked") }
            Out.line("Google Play: what it said of the ${apps(ids.size)} ${ago(now - oldest)} ago is new enough")
            return
        }
        // A problem of the account (not signed in, refused) stops here; one of the network leaves what was said before.
        val found = try {
            call("Google Play") { client.apps.details(stale) }
        } catch (e: IOException) {
            val missing = stale.filter { apps.optJSONObject(it) == null }
            if (missing.isNotEmpty()) throw IOException("cannot ask Google Play about ${missing.joinToString(", ")} (${e.message})", e)
            Out.line("Google Play: cannot ask it now (${e.message}); using what it said before")
            return
        }
        for (id in stale) apps.remove(id)
        for (app in found) {
            if (app.packageName !in stale) continue
            apps.put(
                app.packageName,
                JSONObject().put("name", app.displayName.ifEmpty { app.packageName }).put("versionCode", app.versionCode)
                    .put("versionName", app.versionName).put("offerType", app.offerType).put("size", app.size)
                    .put("restriction", app.restriction.name)
                    // Expansion files (OBB), which go besides the APKs.
                    .put("obb", app.fileList.any { it.type == AppFile.Type.OBB || it.type == AppFile.Type.PATCH })
                    .put("signers", JSONArray(app.certificateSetList.mapNotNull { hex(it.sha256) }))
                    .put(
                        "libraries",
                        JSONArray(app.dependencies.libraries.filter { it.packageName.isNotEmpty() }.map {
                            JSONObject().put("id", it.packageName).put("versionCode", it.versionCode).put("size", it.size)
                        }),
                    )
                    .put("checked", now),
            )
        }
        dir.mkdirs()
        writePrivate(appsFile, JSONObject().put("as", who).put("apps", apps).toString())
        Out.line("Google Play: asked about ${apps(stale.size)}, ${if (who.isEmpty()) "with no account" else "as $who"}" +
            (stale.size - found.count { it.packageName in stale }).let { if (it > 0) " ($it not there)" else "" })
    }

    private fun info(id: String): Info? {
        val json = runCatching { JSONObject(appsFile.readText()) }.getOrNull()?.optJSONObject("apps")?.optJSONObject(id) ?: return null
        val signers = json.optJSONArray("signers") ?: JSONArray()
        val libraries = json.optJSONArray("libraries") ?: JSONArray()
        return Info(
            id, json.getString("name"), json.getLong("versionCode"), json.optString("versionName"), json.optInt("offerType", 1),
            json.optLong("size", -1), json.optString("restriction"), json.optBoolean("obb"),
            (0 until signers.length()).map { signers.getString(it) },
            (0 until libraries.length()).map { i -> libraries.getJSONObject(i).let { Library(it.getString("id"), it.getLong("versionCode"), it.optLong("size", -1)) } },
            json.getLong("checked"),
        )
    }

    sealed class Plan {
        /** Its steps: the libraries it needs that the phone has not, then the app. */
        class Install(val steps: List<Installer.Step>) : Plan()
        class Current(val version: String) : Plan()
        class Problem(val text: String) : Plan()
    }

    /** The libraries this run plans to install already: two apps that need the same one get it once. */
    private val plannedLibraries = HashSet<String>()

    /** What a build does for the app: install or update it (with the libraries it needs), nothing (it is up to date), or a problem. Uses what is here only. */
    fun plan(id: String, installed: Device.Installed?): Plan {
        val app = info(id) ?: return Plan.Problem("$id is not on Google Play (no app has this name there, or Play does not give it to this phone)")
        refusal(app)?.let { return Plan.Problem(it) }
        if (app.obb) {
            return Plan.Problem("$id needs files besides its APKs (expansion files, OBB), which Seraph does not put in place yet: install it with Aurora Store, or take it out of the config")
        }
        if (app.versionCode <= 0) return Plan.Problem("$id: Google Play names no version of it for this phone")
        if (installed != null && installed.signers.isNotEmpty() && app.signers.isNotEmpty() && app.signers.none { it in installed.signers }) {
            return Plan.Problem(
                "$id is installed with another signing key than Google Play's builds (an F-Droid build?), so they cannot update it: " +
                    "uninstall it first (its data goes with it), or take it out of the config",
            )
        }
        if (installed != null && installed.versionCode >= app.versionCode) return Plan.Current(installed.versionName)
        val steps = ArrayList<Installer.Step>()
        for (library in app.libraries) {
            if (Device.hasLibrary(library.id, library.versionCode) || !plannedLibraries.add("${library.id}:${library.versionCode}")) continue
            steps += Installer.Step(
                repo = NAME, id = library.id, name = "${library.id} (a library ${app.name} needs)", version = "${library.versionCode}",
                code = library.versionCode, size = library.size.takeIf { it > 0 } ?: -1, url = page(library.id), sha256 = null,
                listedBy = LISTED_BY, keys = null, keysFrom = "", installed = null, installers = Device.PLAYSTORE, library = true,
                // A library is asked for as Aurora asks for one: by its version, with no offer.
                parts = { files(library.id, library.versionCode, 0, null) },
            )
        }
        val pinned = app.signers.isNotEmpty()
        steps += Installer.Step(
            repo = NAME, id = id, name = app.name, version = app.versionName.ifEmpty { "${app.versionCode}" }, code = app.versionCode,
            size = app.size.takeIf { it > 0 } ?: -1, url = page(id), sha256 = null, listedBy = LISTED_BY,
            keys = if (pinned) app.signers.toSet() else installed?.signers?.takeIf { it.isNotEmpty() },
            keysFrom = if (pinned) {
                "Google Play lists for it"
            } else {
                "of the app installed: Android takes an update only with the same key, so uninstall it first (its data goes with it), or take it out of the config"
            },
            installed = installed, installers = Device.PLAYSTORE,
            // An update names the installed app's key, so Play gives the files that install over it after a key rotation.
            parts = { files(id, app.versionCode, app.offerType, installed?.let { certificate(id) }) },
        )
        return Plan.Install(steps)
    }

    /** The libraries the app needs, by name (none for an app that is not Google Play's). */
    fun libraries(id: String): List<String> = info(id)?.libraries?.map { it.id } ?: emptyList()

    /**
     * Why Google Play does not give the app out, as its details say; null when it does, or when they say it in a way
     * not known here (GENERIC), which the download itself then tells.
     */
    private fun refusal(app: Info): String? = when (app.restriction) {
        "", "AVAILABLE", "GENERIC" -> null
        "UNAVAILABLE_COUNTRY", "UNAVAILABLE_COUNTRY_OR_CARRIER", "UNAVAILABLE_DEVICE_CARRIER" ->
            "${app.id}: Google Play does not give it out in this country (or on this carrier)"
        "UNAVAILABLE_UNLESS_PURCHASED" -> "${app.id}: Google Play gives it only to an account that bought it" +
            if (asking == null) ": sign in with one (⋮ → Google Play account)" else ""
        "UNAVAILABLE_DEVICE_HARDWARE", "UNAVAILABLE_DEVICE_SDK_LEVEL", "UNAVAILABLE_INSUFFICIENT_RAM" ->
            "${app.id}: Google Play says it does not run on this phone"
        "UNAVAILABLE_REMOVED", "UNAVAILABLE_DISCONTINUED", "UNAVAILABLE_BANNED", "BLACKLISTED" -> "${app.id}: it is no longer on Google Play"
        else -> "${app.id}: Google Play does not give it out (${app.restriction.removePrefix("UNAVAILABLE_").lowercase(Locale.ROOT).replace('_', ' ')})"
    }

    /** Where the app's files are now: its base APK first, then its splits, each with its size and SHA-256. */
    private fun files(id: String, versionCode: Long, offerType: Int, certificate: String?): List<Installer.Part> {
        val delivered = call(id) {
            client.delivery.resolve(packageName = id, versionCode = versionCode, offerType = offerType, certificateHash = certificate, patchFormats = emptyList())
        }
        val apks = delivered.filter { it.type == AppFile.Type.BASE || it.type == AppFile.Type.SPLIT }.sortedBy { it.type != AppFile.Type.BASE }
        if (apks.firstOrNull()?.type != AppFile.Type.BASE) throw IOException("$id: Google Play names no base APK for it")
        return apks.map { Installer.Part(it.name, it.url, it.size.takeIf { size -> size > 0 } ?: -1, it.sha256.ifEmpty { null }) }
    }

    /** The installed app's key as Google Play names it (the SHA-1 of its newest certificate, base64url): its delivery then fits it. */
    private fun certificate(id: String): String? = Device.currentCertificate(id)?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-1").digest(it))
    }

    // --- Signing in, on the phone.

    /**
     * Turns the token Google's sign-in page gave (its oauth_token cookie, which lasts minutes) into the account's lasting
     * one (an AAS token), as Google's own setup does and Aurora Store does after its sign-in page.
     */
    private fun lastingToken(email: String, oauthToken: String): Map<String, String> {
        val locale = languages.first()
        val form = FormBody.Builder()
            .add("lang", locale.toLanguageTag())
            .add("google_play_services_version", "19629032")
            .add("sdk_version", "28")
            .add("device_country", locale.country.lowercase(Locale.ROOT))
            .add("Email", email)
            .add("service", "ac2dm")
            .add("get_accountid", "1")
            .add("ACCESS_TOKEN", "1")
            .add("callerPkg", "com.google.android.gms")
            .add("add_account", "1")
            .add("Token", oauthToken)
            .add("callerSig", "38918a453d07199354f8b19af05ec6562ced5788")
            .add("droidguard_results", "null")
            .build()
        val request = Request.Builder().url("https://android.clients.google.com/auth").post(form)
            .header("app", "com.google.android.gms").header("User-Agent", "").build()
        http.newCall(request).execute().use { response ->
            return response.body.string().lineSequence()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1).trim() } }
                .toMap()
        }
    }

    companion object {
        /** This source's name in the config: android.packages.playstore, android.settings.repo.playstore, android.playstore. */
        const val NAME = "playstore"

        /** How long Play's anonymous session lasts. */
        private const val ANONYMOUS_AGE = 8L * 3600 * 1000

        private const val LISTED_BY = "Google Play's list of its files"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** The account signed in on the phone (the app's Google Play account): its email address, then its token, readable by root alone. */
        private fun accountFile(data: File) = File(data, "secrets/google-play")

        private fun signedIn(data: File): Credential? = runCatching { accountFile(data).readLines() }.getOrNull()
            ?.map { it.trim() }?.takeIf { it.size >= 2 && it[0].contains('@') && it[1].isNotEmpty() }?.let { Credential(it[0], it[1]) }

        /** The config's apps from Google Play (android.packages.playstore), as the Nix config checked them. */
        fun apps(android: JSONObject): List<String> {
            val list = android.optJSONObject("packages")?.optJSONArray(NAME) ?: return emptyList()
            return (0 until list.length()).map { i ->
                list.optString(i).takeIf { PACKAGE_NAME.matches(it) }
                    ?: throw Stop("android.packages.$NAME: each app is a package name, like \"com.whatsapp\"")
            }
        }

        /**
         * Signs the phone in to Google Play with an account: reads its email address and the oauth_token Google's sign-in
         * page gave (one a line, from the app's sign-in), turns it into the account's lasting token, checks that Google
         * Play takes it (a session, kept for the builds), and keeps the account in secrets/google-play, for root alone.
         */
        fun login(data: File, input: InputStream): Int {
            val lines = input.bufferedReader().readLines().map { it.trim() }
            val given = lines.getOrNull(0).orEmpty()
            val oauthToken = lines.getOrNull(1).orEmpty()
            if (oauthToken.isEmpty()) throw Stop("playstore-login reads two lines: the account's email address, and the oauth_token of Google's sign-in page")
            val store = PlayStore(data, null)
            val answer = try {
                store.lastingToken(given, oauthToken)
            } catch (e: IOException) {
                throw Stop("cannot reach Google (${e.message}): is the phone online?")
            }
            val token = answer["Token"]
                ?: throw Stop("Google gave no token for ${given.ifEmpty { "the account" }} (${answer["Error"] ?: "it said nothing more"}): sign in again")
            val email = answer["Email"]?.takeIf { it.contains('@') } ?: given.takeIf { it.contains('@') }
                ?: throw Stop("Google did not say which account signed in: sign in again")
            val session = try {
                runBlocking { store.signIn(email, token) }
            } catch (e: PlayException) {
                throw Stop(store.said("Signing in", e))
            } catch (e: IOException) {
                throw Stop("cannot reach Google Play (${e.message}): is the phone online?")
            }
            writePrivate(accountFile(data), "$email\n$token\n", mode = "400")
            Out.line("✔ Signed in to Google Play as $email" + (session.userProfile?.name?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            return 0
        }

        /**
         * What Google Play has of the apps named, for this phone, as the last build's account (or the one signed in, else
         * none) is told: each one's version, size and the libraries it needs, and what a build would do with it; with
         * [files], where its files are now (with an account, Google Play counts that as the account's download). For
         * trying a package name before it goes in the config.
         */
        fun show(data: File, ids: List<String>, files: Boolean): Int {
            ids.firstOrNull { !PACKAGE_NAME.matches(it) }?.let { throw Stop("$it is not a package name (like com.whatsapp)") }
            if (ids.isEmpty()) throw Stop("usage: playstore <apps...> [--files]")
            val profile = File(data, "profile.json")
            val store = PlayStore(data, runCatching { JSONObject(profile.readText()).optJSONObject("android") }.getOrNull())
            try {
                store.refresh(ids, force = true)
            } catch (e: IOException) {
                throw Stop(e.message ?: "cannot ask Google Play")
            }
            for (id in ids) {
                val app = store.info(id)
                if (app == null) {
                    Out.line("$id: not on Google Play (no app has this name there, or Play does not give it to this phone)")
                    continue
                }
                val installed = Device.installed(id)
                Out.line("$id: ${app.name} ${app.versionName} (version code ${app.versionCode}), ${Out.size(app.size.takeIf { it > 0 } ?: -1)}" +
                    app.libraries.joinToString("") { ", needs the library ${it.id} ${it.versionCode}" })
                if (app.signers.isNotEmpty()) Out.line("  signed with (SHA-256): ${app.signers.joinToString(", ")}")
                Out.line("  " + when (val plan = store.plan(id, installed)) {
                    is Plan.Install -> if (installed == null) "a build would install it" else "a build would update it from ${installed.versionName}"
                    is Plan.Current -> "installed, up to date (${plan.version})"
                    is Plan.Problem -> "✘ ${plan.text.removePrefix("$id: ")}"
                })
                if (!files) continue
                try {
                    for (part in store.files(id, app.versionCode, app.offerType, installed?.let { store.certificate(id) })) {
                        Out.line("  ${part.name}  ${Out.size(part.size)}  sha256 ${part.sha256 ?: "not listed"}")
                        Out.line("    ${part.url}")
                    }
                } catch (e: IOException) {
                    Out.line("  ✘ ${e.message?.removePrefix("$id: ")}")
                }
            }
            return 0
        }

        /** Signs the phone out of Google Play: its account and session go; Play's apps then come with no account (the config's account, if it names one, stops builds until one signs in). */
        fun logout(data: File): Int {
            val had = signedIn(data)?.email
            accountFile(data).delete()
            File(data, "repos/$NAME/session.json").delete()
            Out.line(if (had != null) "✔ Signed out of Google Play ($had): its token is gone from the phone" else "No account was signed in to Google Play")
            return 0
        }

        /** Writes a file only root can read ([mode]), in a folder only root can enter, whole or not at all. */
        private fun writePrivate(file: File, text: String, mode: String = "600") {
            val folder = file.parentFile!!
            folder.mkdirs()
            Os.chmod(folder.path, "700".toInt(8))
            val fresh = File(folder, ".${file.name}.new")
            fresh.delete()
            fresh.writeText(text)
            Os.chmod(fresh.path, mode.toInt(8))
            if (!fresh.renameTo(file)) throw IOException("cannot write ${file.path}")
        }

        private fun sha256(text: String) = Net.sha256(text.toByteArray())

        /** A SHA-256 as Google Play names it (base64url) in hex, as the phone names signing keys; null when it is not one. */
        private fun hex(base64url: String): String? = runCatching { Base64.getUrlDecoder().decode(base64url.trimEnd('=')) }.getOrNull()
            ?.takeIf { it.size == 32 }?.joinToString("") { "%02x".format(it) }

        private fun page(id: String) = "https://play.google.com/store/apps/details?id=$id"

        private fun apps(n: Int) = if (n == 1) "1 app" else "$n apps"

        private fun ago(millis: Long) = if (millis < 3_600_000) "${millis / 60_000} min" else "${millis / 3_600_000} h"

        /** A string value, or null when it is missing or JSON's null (which optString would give as "null"). */
        private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}
