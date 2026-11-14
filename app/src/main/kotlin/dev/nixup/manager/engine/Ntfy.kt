package dev.nixup.manager.engine

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * services.ntfy (nix/ntfy.nix): listens to the config's ntfy topics, as ntfy's Android app does, and hands each message
 * to Nixup's app, which shows it as a notification (its EventsReceiver; see NtfyMessages). Its unit runs it as root,
 * through app_process, for as long as the service runs: one connection to each server, for all of its topics that log
 * in alike, reading ntfy's stream of JSON lines (GET <server>/<topic>,<topic>/json).
 *
 * With services.ntfy.unifiedPush, Nixup's app is also a UnifiedPush distributor, as ntfy's app is: an app that picks it
 * (Element, Element X, ...) gets a private topic of its own on the UnifiedPush server, which the app keeps in its
 * registrations file ([Push]). Those topics have a connection of their own, made again each time the file changes, and
 * a message on one is handed to the app it belongs to through Nixup's app (UnifiedPush), not shown; the phone first lets
 * that app use the network for a while, even in Doze, as a push of Google's would.
 *
 * A connection that breaks (another network, the server restarted, no keepalive from the server in [READ_TIMEOUT]) is
 * made again, at once after one that lasted and then less and less often, and it asks the server for what came in the
 * meantime (since=, by the server's own clock: [State]), so nothing the server still keeps is lost. What comes twice
 * (from the second the stream broke in, and the minute before it, for what a server of several nodes writes late) is
 * known by its id and dropped. A topic new to the config starts with what comes once it is first listened to (an app's
 * new UnifiedPush topic, with what came since the app was given it).
 *
 * The phone may sleep between messages: the server's keepalives and messages wake it. A message is handed over under a
 * wake lock, and so is the minute after a connection broke, so the next one is made before the phone sleeps again. A
 * server that refuses the topics or their login is told once, as a problem of the service, and asked again every
 * [REFUSED_WAIT] (a build that changes the config starts the service again anyway).
 */
object Ntfy {
    private const val RECEIVER = "dev.nixup.manager/.EventsReceiver"
    private const val ACTION = "dev.nixup.manager.action.NTFY"
    private const val PUSH_ACTION = "dev.nixup.manager.action.UNIFIED_PUSH_MESSAGE"
    private const val PUSH_SERVER_ACTION = "dev.nixup.manager.action.UNIFIED_PUSH"
    /** The flags of what is handed to the app: received first (FLAG_RECEIVER_FOREGROUND), and by an app that was stopped too. */
    private const val FLAGS = "0x10000020"

    /** ntfy's servers send a keepalive every 45 seconds: three missed, and the connection is taken for broken. */
    private const val READ_TIMEOUT = 150_000
    private const val CONNECT_TIMEOUT = 30_000
    /** A connection open this long broke by chance (another network): the next comes at once. */
    private const val LASTED = 60_000L
    /** The longest wait between tries while a server cannot be reached, or is busy. */
    private const val LONGEST_WAIT = 60_000L
    /** The wait after a server refused the topics or their login. */
    private const val REFUSED_WAIT = 600_000L
    /** Seconds a connection asks for before what was received, for what a server of several nodes writes late. */
    private const val LATE = 60L
    /** The ids of the messages last received, kept to know one that comes twice. */
    private const val SEEN = 500
    /** How long an app a push is for may use the network, in Doze too (ms). */
    private const val PUSH_ALLOWANCE = 30_000
    /** How often the registrations file of UnifiedPush is looked at (ms): it changes when an app registers. */
    private const val PUSH_LOOK = 3_000L
    /** A UnifiedPush topic, as Nixup's app (and ntfy's) makes them: "up" and 12 letters and digits. */
    private val PUSH_TOPIC = Regex("up[A-Za-z0-9]{12}")

    private data class Login(val token: String?, val user: String?, val password: String?)

    /** [push]: the app a UnifiedPush topic's messages are for; [created]: when the app was given it (epoch seconds). */
    private class Topic(val server: String, val name: String, val label: String, val login: Login, val push: String? = null, val created: Long? = null) {
        val url get() = "$server/$name"
    }

    /** The server's answer was not ntfy's stream ([status] 0: the login's files could not be read). */
    private class Refused(val status: Int, val why: String) : IOException(why)

    /** Listens to the topics of [settings] (the unit's argument, from nix/ntfy.nix) for as long as it runs. */
    fun listen(settings: JSONObject): Int {
        val list = settings.optJSONArray("topics") ?: JSONArray()
        val topics = (0 until list.length()).map { i ->
            val topic = list.getJSONObject(i)
            Topic(topic.getString("server").trimEnd('/'), topic.getString("topic"), topic.text("label") ?: topic.getString("topic"), loginOf(topic))
        }
        val push = settings.optJSONObject("unifiedPush")?.let { Push(it) }
        if (topics.isEmpty() && push == null) throw Stop("ntfy: no topics to listen to (services.ntfy.topics)")
        val state = State(File(settings.optString("state", "/var/lib/ntfy/state.json"))) { url -> topics.any { it.url == url } || push?.owns(url) == true }
        Runtime.getRuntime().addShutdownHook(Thread { state.save() })
        // One connection for each server and login, as ntfy's app has them; and one for the apps' UnifiedPush topics.
        val connections = topics.groupBy { it.server to it.login }.values.mapIndexed { index, group ->
            Connection(index, group.first().server, group.first().login, state) { group }
        }.toMutableList()
        // Nixup's app answers apps' registrations with this server, or none (UnifiedPush is off): told at each start, as
        // the app may be new.
        tellPushServer(push?.server)
        if (push != null) connections += Connection(connections.size, push.server, push.login, state) { push.topics() }.also { push.watch(it) }
        state.keepTold(connections.map { it.key })
        connections.map { connection -> Thread({ connection.run() }, "ntfy-${connection.index}").apply { start() } }.forEach { it.join() }
        return 0
    }

    private fun JSONObject.text(key: String) = optString(key).ifEmpty { null }

    private fun loginOf(json: JSONObject) = Login(json.text("token"), json.text("user"), json.text("password"))

    /** Tells Nixup's app the UnifiedPush server its registrations get topics on (null: none, UnifiedPush is off). */
    private fun tellPushServer(server: String?) {
        val extra = if (server == null) emptyList() else listOf("--es", "server", server)
        runCatching {
            ProcessBuilder(listOf("/system/bin/am", "broadcast", "--user", "current", "-f", FLAGS, "-n", RECEIVER, "-a", PUSH_SERVER_ACTION) + extra)
                .redirectErrorStream(true).redirectInput(File("/dev/null")).start().apply {
                    inputStream.readBytes()
                    waitFor(60, TimeUnit.SECONDS)
                }
        }
        log(if (server == null) "UnifiedPush: off" else "UnifiedPush: apps that pick Nixup get their topics on $server")
    }

    /**
     * UnifiedPush (services.ntfy.unifiedPush): the server, its login, and the apps' topics, which Nixup's app keeps in its
     * registrations file (as root, the service reads it where the app wrote it): { "server": …, "apps": [ { "token",
     * "app", "topic", "created" } ] }. Only the topics on this server count (the app moves them when it changes).
     */
    private class Push(json: JSONObject) {
        val server = json.getString("server").trimEnd('/')
        val login = loginOf(json)
        private val file = File(json.getString("registrations"))
        @Volatile private var current: List<Topic> = read()

        fun owns(url: String) = url.startsWith("$server/up")

        fun topics(): List<Topic> = current

        private fun read(): List<Topic> = runCatching {
            val registrations = JSONObject(file.readText())
            if (registrations.optString("server").trimEnd('/') != server) return emptyList()
            val apps = registrations.optJSONArray("apps") ?: return emptyList()
            (0 until apps.length()).mapNotNull { i ->
                val entry = apps.optJSONObject(i) ?: return@mapNotNull null
                val topic = entry.optString("topic").takeIf { PUSH_TOPIC.matches(it) } ?: return@mapNotNull null
                val app = entry.optString("app").takeIf { PACKAGE_NAME.matches(it) } ?: return@mapNotNull null
                Topic(server, topic, app, login, push = app, created = entry.optLong("created").takeIf { it > 0 })
            }.distinctBy { it.name }
        }.getOrDefault(emptyList())

        /** Looks at the file every [PUSH_LOOK]: when its topics change, [connection] is made again with them. */
        fun watch(connection: Connection) {
            Thread({
                // (Read again at its first look: the app may have moved its topics when it was told the server, after the
                // file was first read.)
                var seen = 0L to -1L
                while (true) {
                    Thread.sleep(PUSH_LOOK)
                    val now = file.lastModified() to file.length()
                    if (now == seen) continue
                    seen = now
                    val next = read()
                    if (next.map { it.name to it.push } == current.map { it.name to it.push }) continue
                    current = next
                    log("UnifiedPush: ${next.size} app topic(s) now: ${next.joinToString(", ") { it.push.orEmpty() }.ifEmpty { "none" }}")
                    connection.restart()
                }
            }, "ntfy-unifiedpush").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** The connection to one server for the topics [source] gives, and the next one each time it breaks: for good. */
    private class Connection(val index: Int, val server: String, val login: Login, val state: State, val source: () -> List<Topic>) {
        val host = server.substringAfter("://")
        /** The server and login, which the problem told is kept by (see State.told). */
        val key = listOf(server, login.token.orEmpty(), login.user.orEmpty(), login.password.orEmpty()).joinToString("|")
        /** Its wake locks: while a message is handed over, and for the minute after the connection broke. */
        val handing = "nixup-ntfy-$index"
        val again = "nixup-ntfy-$index-again"
        private val lock = Object()
        /** Its topics changed (UnifiedPush): the connection there is ended, and made again with them at once. */
        @Volatile private var changed = false
        @Volatile private var open: HttpURLConnection? = null

        fun restart() {
            changed = true
            synchronized(lock) { lock.notifyAll() }
            open?.disconnect()
        }

        fun run() {
            var tries = 0
            while (true) {
                changed = false
                val topics = source()
                if (topics.isEmpty()) {
                    // (UnifiedPush with no app registered yet: nothing to listen to until one is.)
                    synchronized(lock) { while (!changed) lock.wait() }
                    continue
                }
                var openedAt = 0L
                val problem = try {
                    stream(topics) { openedAt = SystemClock.elapsedRealtime() }
                    null
                } catch (e: Exception) {
                    e
                }
                if (changed) {
                    log("$host: its topics changed; listening again")
                    continue
                }
                if (openedAt > 0) {
                    if (SystemClock.elapsedRealtime() - openedAt >= LASTED) tries = 0
                    wake(again, 60)
                }
                tries++
                val wait = when {
                    problem is Refused && (problem.status == 429 || problem.status >= 500) -> LONGEST_WAIT
                    problem is Refused -> REFUSED_WAIT
                    else -> minOf(1000L shl minOf(tries - 1, 6), LONGEST_WAIT)
                }
                val why = when (problem) {
                    null -> "the server ended the connection"
                    is Refused -> problem.why
                    else -> problem.message?.let { "${problem.javaClass.simpleName}: $it" } ?: problem.javaClass.simpleName
                }
                log("$host: ${if (openedAt > 0) "connection broke" else "no connection"} ($why); again in ${if (wait >= 60_000) "${wait / 60_000} min" else "${wait / 1000} s"}")
                if (problem is Refused && wait == REFUSED_WAIT && state.told(key) != why) {
                    state.tell(key, why)
                    val title = when (problem.status) {
                        0 -> "ntfy: no login for $host"
                        401, 403 -> "ntfy: $host refused the login"
                        else -> "ntfy: $host refused the topics"
                    }
                    Notify.event("service", "error", title, "$why (${describe(topics)}); asked again every ${REFUSED_WAIT / 60_000} minutes", "service:ntfy")
                }
                // (A change of its topics ends the wait.)
                synchronized(lock) { if (!changed) lock.wait(wait) }
            }
        }

        /** Its topics, as the log and notifications name them (an app's UnifiedPush topic by the app: its name is secret). */
        fun describe(topics: List<Topic>) = topics.joinToString(", ") { it.push?.let { app -> "UnifiedPush for $app" } ?: it.name }

        /** One connection to [topics], until it breaks; [opened] once the server sent its stream. */
        fun stream(topics: List<Topic>, opened: () -> Unit) {
            val since = state.since(topics)
            val connection = (URL("$server/${topics.joinToString(",") { it.name }}/json" + (since?.let { "?since=$it" } ?: "")).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "Nixup")
                authorization()?.let { setRequestProperty("Authorization", it) }
            }
            open = connection
            try {
                if (changed) return
                val status = connection.responseCode
                // (ntfy answers every topic's address: a 404 is another server's, or another address's.)
                if (status == HttpURLConnection.HTTP_NOT_FOUND) throw Refused(status, "HTTP 404: no ntfy server at $server")
                if (status != HttpURLConnection.HTTP_OK) throw Refused(status, "HTTP $status" + (said(connection)?.let { ": $it" } ?: ""))
                connection.inputStream.bufferedReader().use { reader ->
                    var floors: Map<String, Long>? = null
                    while (true) {
                        val line = reader.readLine() ?: return
                        if (line.isBlank()) continue
                        val event = runCatching { JSONObject(line) }.getOrNull()
                        if (floors == null) {
                            // Its first line is ntfy's "open".
                            if (event?.optString("event") != "open") throw Refused(status, "it answers, but not as an ntfy server")
                            floors = state.open(topics, event.optLong("time"))
                            opened()
                            unwake(again)
                            log("$host: listening to ${describe(topics)}" + (since?.let { ", and to what came since ${Out.date(it * 1000)}" } ?: ""))
                            if (state.told(key) != null) {
                                state.tell(key, null)
                                Notify.event("service", "ok", "ntfy listens to $host again", describe(topics), "service:ntfy")
                            }
                        } else if (event != null) {
                            take(topics, event, floors)
                        }
                    }
                }
            } finally {
                open = null
                connection.disconnect()
            }
        }

        /** A line of the stream: a keepalive, or a message (or an update's delete or clear) to hand over unless it came before. */
        fun take(topics: List<Topic>, event: JSONObject, floors: Map<String, Long>) {
            val time = event.optLong("time")
            when (val kind = event.optString("event")) {
                "keepalive" -> state.reached(topics, time)
                "message", "message_delete", "message_clear" -> {
                    val topic = topics.firstOrNull { it.name == event.optString("topic") } ?: return
                    val id = event.optString("id")
                    if (id.isEmpty() || time < (floors[topic.url] ?: 0) || state.seen(id)) return
                    if (topic.push == null) hand(topic, event, kind) else if (kind == "message") push(topic, event)
                    state.received(topic, id, time)
                }
            }
        }

        /** Hands [event] to Nixup's app, which shows it (or takes away the notification it deletes or clears). */
        fun hand(topic: Topic, event: JSONObject, kind: String) {
            val what = when (kind) {
                "message_delete" -> "deleted"
                "message_clear" -> "cleared"
                else -> "message"
            }
            val key = event.optString("sequence_id").ifEmpty { event.optString("id") }
            wake(handing, 30)
            try {
                val said = toApp(ACTION, "--es", "message", event.toString(), "--es", "url", topic.url, "--es", "name", topic.label)
                log("${topic.url.substringAfter("://")}: $what $key" + (said?.let { ", not handed to the app: $it" } ?: ""))
            } finally {
                unwake(handing)
            }
        }

        /**
         * A UnifiedPush message: Nixup's app hands it to the app it is for (with bytes, which am cannot send). That app
         * may use the network for [PUSH_ALLOWANCE] first, in Doze too, to fetch what the push is about.
         */
        fun push(topic: Topic, event: JSONObject) {
            val app = topic.push ?: return
            wake(handing, 30)
            try {
                runCatching {
                    ProcessBuilder("/system/bin/cmd", "deviceidle", "tempwhitelist", "-d", "$PUSH_ALLOWANCE", app)
                        .redirectErrorStream(true).redirectInput(File("/dev/null")).start().apply {
                            inputStream.readBytes()
                            waitFor(20, TimeUnit.SECONDS)
                        }
                }
                val said = toApp(PUSH_ACTION, "--es", "message", event.toString(), "--es", "url", topic.url)
                log("$host: UnifiedPush message ${event.optString("id")} for $app" + (said?.let { ", not handed to the app: $it" } ?: ""))
            } finally {
                unwake(handing)
            }
        }

        /** A broadcast to Nixup's app ([extras] as am takes them); null once it took it, else what went wrong. */
        fun toApp(action: String, vararg extras: String): String? {
            val process = ProcessBuilder(listOf("/system/bin/am", "broadcast", "--user", "current", "-f", FLAGS, "-n", RECEIVER, "-a", action) + extras)
                .redirectErrorStream(true).redirectInput(File("/dev/null")).start()
            val ended = process.waitFor(60, TimeUnit.SECONDS)
            if (!ended) process.destroyForcibly()
            val said = process.inputStream.bufferedReader().readText()
            return if (ended && process.exitValue() == 0 && "Broadcast completed" in said) null
            else said.lines().lastOrNull { it.isNotBlank() } ?: "am did not end"
        }

        /** The login's header, from its files (read for each connection: a build may have put new ones there). */
        fun authorization(): String? {
            login.token?.let { return "Bearer " + read(it).trim() }
            val user = login.user ?: return null
            val password = login.password?.let { read(it).removeSuffix("\n").removeSuffix("\r") }.orEmpty()
            return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        }

        fun read(path: String): String = try {
            File(path).readText()
        } catch (e: IOException) {
            throw Refused(0, "cannot read $path (${e.message})")
        }

        /** What a server that refused says why: ntfy's error, else the start of its answer (not a web page's). */
        fun said(connection: HttpURLConnection): String? {
            val text = runCatching {
                connection.errorStream?.use { input ->
                    val buffer = ByteArray(4096)
                    var have = 0
                    while (have < buffer.size) {
                        val n = input.read(buffer, have, buffer.size - have)
                        if (n < 0) break
                        have += n
                    }
                    String(buffer, 0, have)
                }
            }.getOrNull()?.trim() ?: return null
            return runCatching { JSONObject(text).optString("error").ifEmpty { null } }.getOrNull()
                ?: text.lines().first().take(200).takeIf { it.isNotEmpty() && !it.startsWith("<") }
        }
    }

    /**
     * What was received, kept in the service's state folder across its restarts: for each topic, the server's time it
     * was listened to until (its last message, or the last keepalive), which the next connection asks the server to go
     * on from; and the ids of the last messages, to know one that comes twice. Each server's own clock, never the phone's
     * (but for an app's new UnifiedPush topic: when the phone gave it). And the problem told for each server and login (a
     * refused login), so the first connection that works again takes its notification back, also after a build put a new
     * login and started the service again. [keep]: the topics there are now (one that left is new again when it is back).
     */
    private class State(private val file: File, keep: (String) -> Boolean) {
        private val until = HashMap<String, Long>()
        private val seen = LinkedHashSet<String>()
        private val problems = HashMap<String, String>()
        private var savedAt = 0L

        init {
            runCatching {
                val json = JSONObject(file.readText())
                json.optJSONObject("until")?.let { times -> times.keys().forEach { if (keep(it)) until[it] = times.getLong(it) } }
                json.optJSONArray("seen")?.let { ids -> for (i in 0 until ids.length()) seen += ids.getString(i) }
                json.optJSONObject("told")?.let { told -> told.keys().forEach { problems[it] = told.getString(it) } }
            }
        }

        @Synchronized
        fun told(key: String): String? = problems[key]

        /** [why] was told for [key] (null: it works again). */
        @Synchronized
        fun tell(key: String, why: String?) {
            if (why == null) problems.remove(key) else problems[key] = why
            save()
        }

        /** Only the problems of the connections there are now. */
        @Synchronized
        fun keepTold(keys: List<String>) {
            problems.keys.retainAll(keys.toSet())
        }

        /** What a topic was listened to until; for an app's new UnifiedPush topic, when the app was given it. */
        private fun from(topic: Topic) = until[topic.url] ?: topic.created

        /** Where a connection to [topics] asks the server to start: a minute before the earliest they were listened to until; null for new topics alone (what comes from now on). */
        @Synchronized
        fun since(topics: List<Topic>): Long? = topics.mapNotNull { from(it) }.minOrNull()?.let { maxOf(it - LATE, 1) }

        /**
         * The server opened the stream at [time]: what each topic's messages must not be older than to be shown (a minute
         * before what it was listened to until; for a topic new to the config, what comes from now on).
         */
        @Synchronized
        fun open(topics: List<Topic>, time: Long): Map<String, Long> {
            val floors = topics.associate { it.url to (from(it)?.let { at -> at - LATE } ?: time) }
            for (topic in topics) if (topic.url !in until) until[topic.url] = from(topic) ?: time
            save()
            return floors
        }

        /** A keepalive: all up to [time] has come. (Written at most every ten minutes: a time a little old only makes the next connection ask for more.) */
        @Synchronized
        fun reached(topics: List<Topic>, time: Long) {
            for (topic in topics) until[topic.url] = maxOf(until[topic.url] ?: 0, time)
            if (SystemClock.elapsedRealtime() - savedAt > 600_000) save()
        }

        @Synchronized
        fun seen(id: String) = id in seen

        @Synchronized
        fun received(topic: Topic, id: String, time: Long) {
            seen += id
            while (seen.size > SEEN) seen.remove(seen.first())
            until[topic.url] = maxOf(until[topic.url] ?: 0, time)
            save()
        }

        @Synchronized
        fun save() {
            runCatching {
                val part = File(file.path + ".part")
                val times = JSONObject().apply { for ((topic, time) in until) put(topic, time) }
                val told = JSONObject().apply { for ((key, why) in problems) put(key, why) }
                part.writeText(JSONObject().put("until", times).put("seen", JSONArray(seen.toList())).put("told", told).toString())
                if (!part.renameTo(file)) part.delete()
                savedAt = SystemClock.elapsedRealtime()
            }.onFailure { log("cannot write ${file.path}: ${it.message}") }
        }
    }

    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    /** A line of the service's log, with its time. */
    private fun log(text: String) = Out.line("${synchronized(clock) { clock.format(Date()) }} $text")

    /** Keeps the phone awake (a kernel wake lock, as root) for [seconds] at most, or until [unwake]. */
    private fun wake(name: String, seconds: Int) = runCatching { File("/sys/power/wake_lock").writeText("$name ${seconds * 1_000_000_000L}") }

    private fun unwake(name: String) = runCatching { File("/sys/power/wake_unlock").writeText(name) }
}
