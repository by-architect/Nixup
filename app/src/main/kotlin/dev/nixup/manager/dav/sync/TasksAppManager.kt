/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its TasksAppManager. DAVx⁵ asks the user which tasks app to
 * sync with; Nixup takes it from the config (services.caldav.tasksApp), else the one installed.
 */

package dev.nixup.manager.dav.sync

import android.content.Context
import android.content.pm.PackageManager
import at.bitfire.synctools.storage.TaskProvider
import at.bitfire.synctools.storage.TaskProvider.ProviderName
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.settings.AccountSettings

/**
 * Which tasks app the tasks (VTODO, and jtx Board's journals and notes) are synced with: one at a time, as in DAVx⁵.
 */
class TasksAppManager(private val context: Context = Dav.context) {

    /**
     * The tasks app to sync with: the one the config names ([AccountSettings.tasksApp], by its package), when it is
     * installed; else the first installed of Tasks.org, OpenTasks and jtx Board. Null: none is installed.
     */
    fun currentProvider(settings: AccountSettings?): ProviderName? {
        val wanted = settings?.tasksApp
        if (wanted != null)
            return ProviderName.entries.firstOrNull { it.packageName == wanted || it.authority == wanted }?.takeIf { installed(it) }
        return PREFERRED.firstOrNull { installed(it) }
    }

    private fun installed(provider: ProviderName) =
        context.packageManager.resolveContentProvider(provider.authority, 0) != null

    /** Tells the user that the tasks app is older than the sync needs. */
    fun notifyProviderTooOld(e: TaskProvider.ProviderTooOldException, accountId: AccountId) {
        val label = try {
            context.packageManager.getApplicationInfo(e.provider.packageName, 0).loadLabel(context.packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            e.provider.packageName
        }
        SyncNotificationManager(accountId, context).notifyRetriesFailed(
            SyncDataType.TASKS,
            "$label ${e.installedVersionName} is too old to sync tasks with: it needs ${e.provider.minVersionName} or newer."
        )
    }

    companion object {
        /** Without a choice in the config: the first of these that is installed. */
        val PREFERRED = listOf(ProviderName.TasksOrg, ProviderName.OpenTasks, ProviderName.JtxBoard)
    }

}
