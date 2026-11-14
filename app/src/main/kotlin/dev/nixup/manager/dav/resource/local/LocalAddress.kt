/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.resource.local

import at.bitfire.synctools.mapping.contacts.Contact

interface LocalAddress: LocalResource {

    fun update(data: Contact, fileName: String?, eTag: String?, scheduleTag: String?, flags: Int)

}