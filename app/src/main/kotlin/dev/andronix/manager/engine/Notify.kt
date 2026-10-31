package dev.andronix.manager.engine

import java.util.concurrent.LinkedBlockingQueue

/**
 * Notifications about installs and updates, shown by the app: the engine runs as root outside of it, so it tells
 * the app's UpdatesReceiver with a broadcast (am, run as root, reaches the receiver though it is not exported, and
 * the app though it was never opened). They go out one after another from a thread of their own, so the engine
 * does not wait for am; a progress that a newer one replaces before it went out is dropped.
 */
object Notify {
    private const val APP = "dev.andronix.manager"
    private const val RECEIVER = "$APP/.UpdatesReceiver"
    const val ACTION = "$APP.action.UPDATES"

    private class Event(val progress: Boolean, val args: List<String>)

    private val queue = LinkedBlockingQueue<Event>()
    private var sender: Thread? = null

    fun progress(title: String, text: String, done: Int, total: Int) =
        send("progress", title, text, progress = true, "--ei", "done", "$done", "--ei", "total", "$total")

    fun done(title: String, text: String, failed: Boolean) = send("done", title, text, progress = false, "--ez", "failed", "$failed")

    fun available(title: String, text: String) = send("available", title, text, progress = false)

    @Synchronized
    private fun send(event: String, title: String, text: String, progress: Boolean, vararg extras: String) {
        queue.put(
            Event(
                progress,
                listOf(
                    "am", "broadcast", "--user", "current", "-f", "0x20", "-n", RECEIVER, "-a", ACTION,
                    "--es", "event", event, "--es", "title", title, "--es", "text", text,
                ) + extras,
            ),
        )
        if (sender == null) {
            sender = Thread {
                while (true) {
                    val next = queue.take()
                    if (next.args.isEmpty()) break
                    if (next.progress && queue.peek()?.progress == true) continue
                    runCatching { Device.run(*next.args.toTypedArray()) }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Waits (a while at most) for the notifications still to go out: the engine is about to end. */
    fun flush() {
        val thread = sender ?: return
        queue.put(Event(false, emptyList()))
        thread.join(20_000)
    }
}
