package dev.nixup.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * What the module tells the app (as root, with am broadcast; the receiver is not exported, so only root and the system
 * can): an event (json: a build, backup or update that started or ended, a service that failed, Tailscale's
 * connection; see the module's notify.sh), the progress of installs, or "schedule" alone. Each one also makes sure the
 * update check and the backup check are scheduled.
 */
class EventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.channels(context)
        UpdateJob.schedule(context)
        BackupJob.schedule(context)
        intent.getStringExtra("json")?.let { json -> Event.of(json)?.let { Notifications.event(context, it) } }
        if (intent.getStringExtra("event") == "progress") {
            Notifications.progress(
                context, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty(),
                intent.getIntExtra("done", 0), intent.getIntExtra("total", 0),
            )
        }
    }
}
