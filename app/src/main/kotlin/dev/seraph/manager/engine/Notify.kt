package dev.seraph.manager.engine

import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * What the engine tells the app, which shows it: the engine runs as root outside of it. Events (a backup that started
 * or ended, updates found, a service that failed) go through the module's notify.sh, into Seraph's journal of events
 * (the app's Logs tab) and to the app's EventsReceiver as notifications; the progress of installs goes straight to
 * the receiver, and is kept nowhere. Both go out one after another from a thread of their own, so the engine does not
 * wait for am; a progress that a newer one replaces before it went out is dropped.
 */
object Notify {
    private const val APP = "dev.seraph.manager"
    private const val RECEIVER = "$APP/.EventsReceiver"
    const val ACTION = "$APP.action.EVENT"

    /** The module's folder: the engine runs from its copy of the app (CLASSPATH=<module>/app.apk). */
    val module: File? get() = System.getenv("CLASSPATH")?.let { File(it).parentFile }?.takeIf { File(it, "notify.sh").isFile }

    /** The kind of the run the engine is part of (SERAPH_KIND, from the module's scripts): build, update, backup, ... */
    val kind: String get() = System.getenv("SERAPH_KIND").orEmpty()

    private class Message(val progress: Boolean, val command: List<String>, val quiet: Boolean = false)

    private val queue = LinkedBlockingQueue<Message>()
    private var sender: Thread? = null

    /** Which app is downloading or installing, and how many of how many are done. */
    fun progress(title: String, text: String, done: Int, total: Int) = send(
        Message(
            true,
            listOf(
                "am", "broadcast", "--user", "current", "-f", "0x20", "-n", RECEIVER, "-a", ACTION, "--es", "event", "progress",
                "--es", "title", title, "--es", "text", text, "--ei", "done", "$done", "--ei", "total", "$total",
            ),
        ),
    )

    /**
     * An event (see module/notify.sh): [kind] build, update, backup, service, connection or git; [level] start, ok,
     * info, warn or error; [key] what a newer notification of the same thing replaces; [quiet]: into the journal only.
     */
    fun event(kind: String, level: String, title: String, text: String = "", key: String = kind, quiet: Boolean = false) {
        val script = module?.let { File(it, "notify.sh") } ?: return
        send(Message(false, listOf("sh", script.path, kind, level, title, text, key), quiet))
    }

    /** How an install or update ended: in a build, whose own end says it, only kept in the journal. */
    fun installed(title: String, text: String, failed: Boolean) {
        val inBuild = kind == "build"
        event(if (inBuild) "build" else "update", if (failed) "error" else "ok", title, text, key = if (inBuild) "install" else "update", quiet = inBuild && !failed)
    }

    @Synchronized
    private fun send(message: Message) {
        queue.put(message)
        if (sender == null) {
            sender = Thread {
                while (true) {
                    val next = queue.take()
                    if (next.command.isEmpty()) break
                    if (next.progress && queue.peek()?.progress == true) continue
                    runCatching {
                        val process = ProcessBuilder(next.command).redirectErrorStream(true)
                        if (next.quiet) process.environment()["SERAPH_QUIET"] = "1"
                        process.start().apply {
                            outputStream.close()
                            inputStream.readBytes()
                            waitFor()
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Waits (a while at most) for what is still to go out: the engine is about to end. */
    fun flush() {
        val thread = sender ?: return
        queue.put(Message(false, emptyList()))
        thread.join(20_000)
    }
}
