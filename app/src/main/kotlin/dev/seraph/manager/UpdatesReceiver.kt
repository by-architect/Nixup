package dev.seraph.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * What the module's engine tells about installs and updates (as root, with am broadcast; the receiver is not
 * exported, so only root and the system can): shown as notifications. Each one also makes sure the periodic
 * update check is scheduled, and the module sends "schedule" after each build and boot for that alone.
 */
class UpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.channels(context)
        UpdateJob.schedule(context)
        BackupJob.schedule(context)
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        when (intent.getStringExtra("event")) {
            "progress" -> Notifications.progress(context, title, text, intent.getIntExtra("done", 0), intent.getIntExtra("total", 0))
            "done" -> Notifications.result(context, title, text, intent.getBooleanExtra("failed", false))
            "available" -> Notifications.available(context, title, text)
        }
    }
}
