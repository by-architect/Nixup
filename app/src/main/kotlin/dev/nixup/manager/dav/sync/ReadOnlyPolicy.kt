/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import dev.nixup.manager.dav.resource.local.LocalCollection
import java.util.Optional
import java.util.logging.Logger

/**
 * Handles local changes in a read-only collection: instead of pushing them to the server,
 * resets them locally so that they will be downloaded again and overwritten with the server's version.
 */
class ReadOnlyPolicy(
    private val logger: Logger
) {

    /**
     * Restores locally deleted resources from the server instead of deleting them from the (read-only) server.
     *
     * @return whether local resources have been restored so that a synchronization is always necessary
     */
    suspend fun resetDeleted(collection: LocalCollection<*>): Boolean {
        var modified = false
        collection.findDeleted().collect { local ->
            logger.warning("Restoring locally deleted resource (read-only collection!)")
            local.withExceptionContext {
                local.resetDeleted()
            }
            modified = true
        }

        /* When a resource has been inserted to a read-only collection it's not enough to force
        synchronization (by returning true), we also need to make sure all resources are downloaded again
        (force full re-sync) so that local additions are deleted again because they're not on the server. */
        if (modified)
            collection.lastSyncState = null

        return modified
    }

    /**
     * Resets locally modified entries to ETag=null instead of uploading them to the (read-only) server.
     *
     * @return whether local resources have been reset so that a synchronization is always necessary
     */
    suspend fun resetDirty(collection: LocalCollection<*>): Boolean {
        var modified = false
        collection.findDirty().collect { local ->
            logger.warning("Resetting locally modified resource to ETag=null (read-only collection!)")
            local.withExceptionContext {
                local.clearDirty(
                    fileName = Optional.empty(),
                    eTag = null,
                    scheduleTag = null
                )
            }
            modified = true
        }

        /* When a resource has been inserted to a read-only collection it's not enough to force
        synchronization (by returning true), we also need to make sure all resources are downloaded again
        (force full re-sync) so that local additions are overwritten by the server items. */
        if (modified)
            collection.lastSyncState = null

        return modified
    }

}
