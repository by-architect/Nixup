/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync.groups

import dev.nixup.manager.dav.resource.local.LocalAddressBook
import at.bitfire.synctools.mapping.contacts.Contact
import java.util.Optional
import java.util.logging.Logger

class CategoriesStrategy(val addressBook: LocalAddressBook): ContactGroupStrategy {

    private val logger: Logger
        get() = Logger.getGlobal()

    override suspend fun resolveLocalGroupChanges() {
        // groups with DELETED=1: set all members to dirty, then remove group
        addressBook.findDeletedGroups().collect { group ->
            logger.fine("Finally removing group $group")
            group.markMembersDirty()
            group.androidGroup.delete()
        }

        // groups with DIRTY=1: mark all members as dirty, then clean DIRTY flag of group
        addressBook.findDirtyGroups().collect { group ->
            logger.fine("Marking members of modified group $group as dirty")
            group.markMembersDirty()
            group.clearDirty(Optional.empty(), null)
        }
    }

    override fun verifyContactBeforeSaving(contact: Contact) {
        if (contact.group || contact.members.isNotEmpty()) {
            logger.warning("Received group vCard although group method is CATEGORIES. Saving as regular contact")
            contact.group = false
            contact.members.clear()
        }
    }

    override suspend fun postProcess() {
        logger.info("Removing empty groups")
        addressBook.ab.deleteGroupsWithoutMembers()
    }

}