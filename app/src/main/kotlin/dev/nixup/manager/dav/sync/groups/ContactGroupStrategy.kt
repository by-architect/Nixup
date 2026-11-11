/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync.groups

import at.bitfire.synctools.mapping.contacts.Contact

interface ContactGroupStrategy {

    /**
     * Local-only group housekeeping (e.g. dissolving groups, propagating group-level changes to
     * member contacts) that must happen regardless of whether local changes will be uploaded.
     */
    suspend fun resolveLocalGroupChanges() {}

    /**
     * Prepares group state for upload. Only meaningful when local changes are actually pushed
     * to the server.
     */
    suspend fun beforeUploadDirty() {}

    fun verifyContactBeforeSaving(contact: Contact)
    suspend fun postProcess()

}