package dev.nixup.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * What the module tells the app (as root, with am broadcast; the receiver is not exported, so only root and the system
 * can): an event (json: a build, backup or update that started or ended, a service that failed, Tailscale's
 * connection; see the module's notify.sh), the progress of installs, or "schedule" alone. Each one also makes sure the
 * update check and the backup check are scheduled. And a message of services.ntfy's topics, from its listener (the
 * engine's Ntfy), which NtfyMessages shows; and for UnifiedPush (services.ntfy.unifiedPush), the server the listener
 * has the apps' topics on (or none), and a push for an app, which UnifiedPush hands to it.
 */
class EventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NtfyMessages.ACTION -> return NtfyMessages.show(context, intent.getStringExtra("message").orEmpty(), intent.getStringExtra("url").orEmpty(), intent.getStringExtra("name").orEmpty())
            UnifiedPush.SERVER_ACTION -> return UnifiedPush.configure(context, intent.getStringExtra("server")?.ifEmpty { null })
            UnifiedPush.MESSAGE_ACTION -> return UnifiedPush.deliver(context, intent.getStringExtra("url").orEmpty(), intent.getStringExtra("message").orEmpty())
        }
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
