/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its SyncNotificationManager, in Nixup's notifications (the
 * "Calendar and contacts sync" channel), without DAVx⁵'s debug info and settings screens.
 */

package dev.nixup.manager.dav.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import dev.nixup.manager.MainActivity
import dev.nixup.manager.Notifications
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.resource.local.LocalResource
import io.ktor.http.Url
import java.io.IOException

class SyncNotificationManager(
    val accountId: AccountId,
    private val context: Context = Dav.context
) {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** The account as the config names it (services.caldav.accounts.<name>), with its name on the phone when that is another. */
    private val accountLabel: String
        get() {
            val name = runCatching { android.accounts.AccountManager.get(context).getUserData(accountId, Dav.KEY_NAME) }.getOrNull()
            return if (name != null && name != accountId.name) "$name (${accountId.name})" else accountId.name
        }

    /**
     * Tells the user that the content provider is missing or disabled ([dismissProviderError] takes it back).
     */
    fun notifyProviderError(authority: String) {
        val text = when (authority) {
            ContactsContract.AUTHORITY -> "Android's contacts storage is turned off or missing: the address books cannot be synced."
            CalendarContract.AUTHORITY -> "Android's calendar storage is turned off or missing: the calendars cannot be synced."
            else -> {
                Dav.logger.warning("Content provider error for unknown authority: $authority")
                return
            }
        }
        notify(authority, "$accountLabel: cannot sync", text, error = true)
    }

    fun dismissProviderError(authority: String) = dismiss(authority)

    /**
     * Tells the user that an exception stopped the sync of a collection ([title]): [message]. Network errors go quietly,
     * as DAVx⁵ shows them (they mostly pass by themselves).
     */
    fun notifyException(
        syncDataType: SyncDataType,
        collectionId: Long,
        message: String,
        title: String,
        e: Throwable,
        local: LocalResource?,
        remote: Url?
    ) {
        val text = buildString {
            append(message)
            if (e is UnauthorizedException)
                append(" Each build sets the password from services.caldav.accounts' passwordFile: check it, and build again.")
            remote?.let { append("\n$it") }
            local?.let { append("\n(${it.javaClass.simpleName} #${it.id})") }
        }
        notify(buildTag(collectionId), "$accountLabel: $title", text, error = true, quiet = e is IOException)
    }

    /** Tells the user that a resource could not be read and was passed over. */
    fun notifyInvalidResource(
        dataType: SyncDataType,
        collectionId: Long,
        e: Throwable,
        remote: Url?,
        title: String
    ) {
        notify("invalid-" + buildTag(collectionId), "$accountLabel: $title",
            "Passed over one or more items the server has that cannot be read (${e.message ?: e.javaClass.simpleName})" +
                (remote?.let { ": $it" } ?: ""), error = false, quiet = true)
    }

    /** Tells the user that syncs keep failing with a passing error (no network, a server away). */
    fun notifyRetriesFailed(dataType: SyncDataType, message: String) =
        notify("retries-$dataType", "$accountLabel: cannot sync", message, error = true, quiet = true)

    fun dismissRetriesFailed(dataType: SyncDataType) = dismiss("retries-$dataType")

    fun dismissCollectionError(collectionId: Long) = dismiss(buildTag(collectionId))

    // helpers

    private fun buildTag(collectionId: Long) = "${accountId.name}-$collectionId"

    private fun notify(tag: String, title: String, text: String, error: Boolean, quiet: Boolean = false) {
        try {
            Notifications.channels(context)
            val notification = Notification.Builder(context, Notifications.SYNC_CHANNEL)
                .setSmallIcon(if (error) android.R.drawable.stat_notify_sync_noanim else android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .apply { if (quiet) setPriority(Notification.PRIORITY_MIN) }
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .build()
            manager.notify(TAG_PREFIX + tag, ID, notification)
        } catch (e: Exception) {
            Dav.logger.warning("Couldn't show notification: $e")
        }
    }

    private fun dismiss(tag: String) {
        runCatching { manager.cancel(TAG_PREFIX + tag, ID) }
    }

    companion object {
        private const val TAG_PREFIX = "dav:"
        private const val ID = 7001
    }

}
