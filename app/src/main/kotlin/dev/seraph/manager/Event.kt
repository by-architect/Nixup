package dev.seraph.manager

import org.json.JSONObject

/**
 * One event of Seraph's journal (/data/adb/seraph/events, written by the module's notify.sh): what it is about
 * ([kind]: build, update, backup, service, connection, git), how it went ([level]: start, ok, info, warn, error), who
 * started it ([by]: app, job, boot, adb, ssh, ...), and the run it is part of, whose log is
 * /data/adb/seraph/logs/<run>.log. [quiet]: kept in the journal only, never a notification.
 */
class Event(
    val time: Long,
    val kind: String,
    val level: String,
    val title: String,
    val text: String,
    val key: String,
    val by: String,
    val run: String,
    val quiet: Boolean,
) {
    val problem get() = level == "error" || level == "warn"

    companion object {
        fun of(line: String): Event? = runCatching {
            val json = JSONObject(line)
            Event(
                json.optLong("time") * 1000, json.optString("kind"), json.optString("level"), json.optString("title"),
                json.optString("text"), json.optString("key").ifEmpty { json.optString("kind") }, json.optString("by"),
                json.optString("run"), json.optBoolean("quiet"),
            )
        }.getOrNull()
    }
}
