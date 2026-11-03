package dev.seraph.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The app's notifications, for what the module tells (see EventsReceiver): a channel for each kind of event, which
 * Android's settings can turn off one by one, and one for what is going on now. An event replaces the one before
 * about the same thing (its key: a build, a backup, one service, Tailscale's connection), and its end takes the place
 * of its start. What is about the phone's security (the SSH server, adb, secrets) is never told: the module sends
 * nothing for it.
 */
object Notifications {
    private const val PROGRESS = "progress"
    private const val INSTALL_ID = 1

    /** Each kind of event's channel, and its name. */
    private val CHANNELS = linkedMapOf(
        "builds" to R.string.channel_builds,
        "updates" to R.string.channel_updates,
        "backups" to R.string.channel_backups,
        "services" to R.string.channel_services,
        "connections" to R.string.channel_connections,
    )

    private fun channelOf(kind: String) = when (kind) {
        "update" -> "updates"
        "backup" -> "backups"
        "service" -> "services"
        "connection" -> "connections"
        else -> "builds"
    }

    fun channels(context: Context) {
        val manager = manager(context)
        // The earlier app's one channel for all results.
        manager.deleteNotificationChannel("results")
        manager.createNotificationChannels(
            listOf(NotificationChannel(PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW)) +
                CHANNELS.map { (id, name) -> NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_DEFAULT) },
        )
    }

    /** Which app is downloading or installing, and how many of how many are done. */
    fun progress(context: Context, title: String, text: String, done: Int, total: Int) {
        val notification = Notification.Builder(context, PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(context.getString(R.string.progress_count, done, total))
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, MainActivity.TAB_UPDATES))
            .build()
        manager(context).notify(INSTALL_ID, notification)
    }

    /**
     * An event: a start shows until its end (low, in the progress channel), an end takes its place in its kind's
     * channel. One the user started in the app while looking at it is not told again; nor is a quiet one.
     */
    fun event(context: Context, event: Event) {
        val manager = manager(context)
        val running = ("start:" + event.key).hashCode()
        if (event.level != "start") {
            manager.cancel(running)
            if (event.kind == "update" || event.kind == "build") manager.cancel(INSTALL_ID)
        }
        if (event.quiet || (event.by == "app" && MainActivity.visible)) return
        val tab = if (event.kind == "update" && event.level == "info") MainActivity.TAB_UPDATES else MainActivity.TAB_LOGS
        val builder = if (event.level == "start") {
            // Its end replaces it; one that never comes (a run that was killed) leaves it for 3 hours at most.
            Notification.Builder(context, PROGRESS)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(3 * 3_600_000L)
        } else {
            Notification.Builder(context, channelOf(event.kind)).setAutoCancel(true)
        }
        builder.setSmallIcon(if (event.problem) android.R.drawable.stat_notify_error else R.drawable.ic_stat_seraph)
            .setColor(context.getColor(Logs.color(event.level)))
            .setContentTitle(event.title)
            .setWhen(event.time)
            .setShowWhen(true)
            .setContentIntent(open(context, tab))
        if (event.text.isNotEmpty()) builder.setContentText(event.text).setStyle(Notification.BigTextStyle().bigText(event.text))
        manager.notify(if (event.level == "start") running else event.key.hashCode(), builder.build())
    }

    private fun open(context: Context, tab: String): PendingIntent = PendingIntent.getActivity(
        context, tab.hashCode(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
