/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import at.bitfire.synctools.storage.TaskProvider

enum class SyncDataType {

    CONTACTS,
    EVENTS,
    TASKS;

    /**
     * Returns authorities which exist for this sync data type. Used on [TASKS] the method
     * may return an empty list if there are no tasks providers (installed tasks apps).
     *
     * @return list of authorities matching this data type
     */
    fun possibleAuthorities(): List<String> =
        when (this) {
            CONTACTS -> listOf(ContactsContract.AUTHORITY)
            EVENTS -> listOf(CalendarContract.AUTHORITY)
            TASKS -> TaskProvider.ProviderName.entries.map { it.authority }
        }

    companion object {

        fun fromAuthority(authority: String): SyncDataType {
            return when (authority) {
                ContactsContract.AUTHORITY ->
                    CONTACTS
                CalendarContract.AUTHORITY ->
                    EVENTS
                TaskProvider.ProviderName.JtxBoard.authority,
                TaskProvider.ProviderName.TasksOrg.authority,
                TaskProvider.ProviderName.OpenTasks.authority ->
                    TASKS
                else -> throw IllegalArgumentException("Unknown authority: $authority")
            }
        }

    }

}
