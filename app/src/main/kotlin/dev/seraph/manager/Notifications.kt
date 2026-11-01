package dev.seraph.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Notifications about installing and updating apps, asked for by the module's engine (see UpdatesReceiver). */
object Notifications {
    private const val PROGRESS = "progress"
    private const val RESULTS = "results"
    private const val PROGRESS_ID = 1
    private const val RESULT_ID = 2

    fun channels(context: Context) {
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT),
            ),
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
            .setContentIntent(openUpdates(context))
            .build()
        manager(context).notify(PROGRESS_ID, notification)
    }

    /** How it ended: the progress goes, this stays until tapped. */
    fun result(context: Context, title: String, text: String, failed: Boolean) {
        manager(context).cancel(PROGRESS_ID)
        notify(context, title, text, if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
    }

    /** Updates are there, to be installed from the Updates tab. */
    fun available(context: Context, title: String, text: String) =
        notify(context, title, text, android.R.drawable.stat_notify_sync)

    private fun notify(context: Context, title: String, text: String, icon: Int) {
        val notification = Notification.Builder(context, RESULTS)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openUpdates(context))
            .build()
        manager(context).notify(RESULT_ID, notification)
    }

    private fun openUpdates(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
