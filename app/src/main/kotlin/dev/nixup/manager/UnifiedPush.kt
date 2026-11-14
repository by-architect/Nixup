package dev.nixup.manager

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Nixup's app as a UnifiedPush distributor (services.ntfy.unifiedPush, nix/ntfy.nix), as ntfy's app is one: an app that
 * gets its pushes through UnifiedPush (Element, Element X, ...) can pick Nixup in its notification settings. It asks for
 * an endpoint ([UnifiedPushReceiver], REGISTER), gets a private ntfy topic of its own on the UnifiedPush server, and gives
 * that address to its server (a Matrix homeserver), which sends its pushes there. The service's listener (the engine's
 * Ntfy, as root) listens to those topics, and hands each push to the app here ([deliver]), which hands it to the app it
 * is for, as UnifiedPush's MESSAGE: the app shows its own notification, as with a push of Google's.
 *
 * The registrations are in the app's files ([FILE]), where the listener reads them. The listener tells the server at
 * each of its starts ([configure]); with none (UnifiedPush off, or services.ntfy gone) every app is told it is
 * unregistered, and Nixup is no distributor apps can pick (its receiver and link activity are turned off).
 *
 * UnifiedPush's Android spec: https://unifiedpush.org/developers/spec/android/
 */
object UnifiedPush {
    const val SERVER_ACTION = "dev.nixup.manager.action.UNIFIED_PUSH"
    const val MESSAGE_ACTION = "dev.nixup.manager.action.UNIFIED_PUSH_MESSAGE"
    private const val FILE = "unifiedpush.json"

    private const val CONNECTOR = "org.unifiedpush.android.connector"
    /** The PendingIntent apps' identity comes with (below Android 14), and the link activity's answer: aimed at this app, which there is not. */
    private const val DUMMY_APP = "org.unifiedpush.dummy_app"

    private class Registration(val token: String, val app: String, val topic: String, val created: Long)

    private class Registrations(var server: String?, val apps: MutableList<Registration>)

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    private fun load(context: Context): Registrations = runCatching {
        val json = JSONObject(file(context).readText())
        val apps = json.optJSONArray("apps") ?: JSONArray()
        Registrations(
            json.optString("server").ifEmpty { null },
            (0 until apps.length()).mapNotNull { i ->
                apps.optJSONObject(i)?.let { Registration(it.optString("token"), it.optString("app"), it.optString("topic"), it.optLong("created")) }
            }.toMutableList(),
        )
    }.getOrDefault(Registrations(null, mutableListOf()))

    /** Written whole, then renamed into place: the listener never reads half of it. */
    @Synchronized
    private fun save(context: Context, registrations: Registrations) {
        val json = JSONObject().put("server", registrations.server ?: "").put(
            "apps",
            JSONArray(registrations.apps.map { JSONObject().put("token", it.token).put("app", it.app).put("topic", it.topic).put("created", it.created) }),
        )
        val part = File(context.filesDir, "$FILE.part")
        part.writeText(json.toString())
        part.renameTo(file(context))
    }

    /**
     * A private topic: "up" and 12 letters and digits, as ntfy's app makes them. ntfy takes only these for UnifiedPush
     * topics: on a server that limits pushes by their subscriber (ntfy.sh), a push to another topic is refused.
     */
    private fun newTopic(): String {
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return "up" + (1..12).map { letters[random.nextInt(letters.length)] }.joinToString("")
    }

    private val TOPIC = Regex("up[A-Za-z0-9]{12}")

    /** The endpoint an app gives its server: the topic's address, with up=1 as ntfy's UnifiedPush addresses have it. */
    private fun endpoint(server: String, topic: String) = "$server/$topic?up=1"

    /**
     * The listener's word at its start: the UnifiedPush [server], or null (off). A new server moves every app to a topic
     * on it (each is given its new endpoint), as does a topic not of ntfy's form; none unregisters them all.
     */
    fun configure(context: Context, server: String?) {
        val registrations = load(context)
        val old = registrations.server
        setDistributor(context, server != null)
        if (server == null) {
            for (registration in registrations.apps) tell(context, registration.app, "UNREGISTERED", registration.token)
            registrations.apps.clear()
        }
        val moving = if (server == null) emptyList() else registrations.apps.filter { old != server || !TOPIC.matches(it.topic) }
        val moved = moving.map { Registration(it.token, it.app, newTopic(), now()) }
        registrations.apps.removeAll(moving)
        registrations.apps += moved
        registrations.server = server
        save(context, registrations)
        if (server != null) for (registration in moved) tell(context, registration.app, "NEW_ENDPOINT", registration.token, endpoint(server, registration.topic))
    }

    /** Whether apps can pick Nixup as their distributor: they find it by its receiver of REGISTER (and its link activity). */
    private fun setDistributor(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (component in listOf(UnifiedPushReceiver::class.java, UnifiedPushLinkActivity::class.java)) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, component), state, PackageManager.DONT_KILL_APP)
        }
    }

    /** [app] asks for an endpoint, for its registration [token]: the one it has, or a new topic. */
    fun register(context: Context, app: String, token: String) {
        val registrations = load(context)
        val server = registrations.server ?: return tell(context, app, "REGISTRATION_FAILED", token, reason = "ACTION_REQUIRED")
        val registration = registrations.apps.firstOrNull { it.token == token && it.app == app }
            ?: Registration(token, app, newTopic(), now()).also {
                registrations.apps += it
                save(context, registrations)
            }
        tell(context, app, "NEW_ENDPOINT", token, endpoint(server, registration.topic))
    }

    /** [app] (null: whichever app registered [token]) gives its registration up; it is told so. */
    fun unregister(context: Context, app: String?, token: String) {
        val registrations = load(context)
        val gone = registrations.apps.filter { it.token == token && (app == null || it.app == app) }
        if (gone.isEmpty()) return
        registrations.apps.removeAll(gone)
        save(context, registrations)
        for (registration in gone) tell(context, registration.app, "UNREGISTERED", registration.token)
    }

    /**
     * A push the listener received on topic [url] ([json]: ntfy's message), for the app that topic is for, as bytes (ntfy
     * sends a body that is not text in base64). One for an app no longer installed takes its registration away.
     */
    fun deliver(context: Context, url: String, json: String) {
        val message = runCatching { JSONObject(json) }.getOrNull() ?: return
        val registrations = load(context)
        val server = registrations.server ?: return
        val registration = registrations.apps.firstOrNull { "$server/${it.topic}" == url } ?: return
        val installed = runCatching { context.packageManager.getPackageInfo(registration.app, 0) }.isSuccess
        if (!installed) {
            registrations.apps.remove(registration)
            save(context, registrations)
            return
        }
        val text = message.optString("message")
        val bytes = if (message.optString("encoding") == "base64") runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return else text.toByteArray()
        context.sendBroadcast(
            Intent("$CONNECTOR.MESSAGE").setPackage(registration.app).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("token", registration.token)
                .putExtra("bytesMessage", bytes)
                // (The text too, for apps with a connector older than bytesMessage.)
                .putExtra("message", String(bytes))
                .putExtra("id", message.optString("id")),
        )
    }

    /** UnifiedPush's word to [app]: NEW_ENDPOINT, REGISTRATION_FAILED or UNREGISTERED. */
    private fun tell(context: Context, app: String, what: String, token: String, endpoint: String? = null, reason: String? = null) {
        val intent = Intent("$CONNECTOR.$what").setPackage(app).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("token", token)
        endpoint?.let { intent.putExtra("endpoint", it) }
        reason?.let { intent.putExtra("reason", it) }
        context.sendBroadcast(intent)
    }

    private fun now() = System.currentTimeMillis() / 1000

    /** The PendingIntent that says it is Nixup (the link activity's answer), aimed at UnifiedPush's dummy app. */
    fun identity(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent("$DUMMY_APP.ACTION").setPackage(DUMMY_APP), PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * What apps ask Nixup as their UnifiedPush distributor (see UnifiedPush): REGISTER, UNREGISTER, MESSAGE_ACK. The app that
 * asks is known by Android (Android 14 and newer: the broadcast shares its sender), by the PendingIntent it sends along
 * (older), or, from an older connector, by the package it names. Exported, as any app may ask; on only while
 * services.ntfy.unifiedPush is.
 */
class UnifiedPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")?.takeIf { it.isNotEmpty() && it.length <= 100 } ?: return
        when (intent.action) {
            "org.unifiedpush.android.distributor.REGISTER" -> sender(intent)?.let { UnifiedPush.register(context, it, token) }
            "org.unifiedpush.android.distributor.UNREGISTER" -> UnifiedPush.unregister(context, sender(intent), token)
            // (Nothing waits for an ack here: what was handed over is not sent again.)
            "org.unifiedpush.android.distributor.MESSAGE_ACK" -> {}
        }
    }

    @Suppress("DEPRECATION")
    private fun sender(intent: Intent): String? {
        if (Build.VERSION.SDK_INT >= 34) sentFromPackage?.let { return it }
        val pi = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("pi", PendingIntent::class.java) else intent.getParcelableExtra("pi")
        pi?.creatorPackage?.let { return it }
        return intent.getStringExtra("application")?.takeIf { it.isNotEmpty() }
    }
}

/**
 * UnifiedPush's link activity (unifiedpush://link): an app's connector opens it for a result, to know Nixup for sure;
 * it answers with a PendingIntent of Nixup's, and ends at once.
 */
class UnifiedPushLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingPackage != null && intent?.data != null) setResult(RESULT_OK, Intent().putExtra("pi", UnifiedPush.identity(this)))
        else setResult(RESULT_CANCELED)
        finish()
    }
}
