/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

/**
 * Result of a sync operation from [Syncer], used by [dev.nixup.manager.dav.sync.worker.BaseSyncWorker]
 * to determine whether there will be retries.
 */
data class SyncResult(
    var hardError: Boolean = false,
    var softError: Boolean = false,
    var delayUntil: Long = 0
) {

    /**
     * Whether a hard or a soft error occurred.
     */
    val hasError: Boolean
        get() = hardError || softError

}
