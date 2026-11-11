/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.servicedetection

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.carddav.CardDAV
import at.bitfire.dav4jvm.property.push.WebDAVPush
import at.bitfire.dav4jvm.property.webdav.WebDAV
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.db.Service
import dev.nixup.manager.dav.db.ServiceType

object ServiceDetectionUtils {

    /**
     * WebDAV properties to ask for in a PROPFIND request on a collection.
     */
    fun collectionQueryProperties(@ServiceType serviceType: String): Array<Property.Name> =
        arrayOf(                        // generic WebDAV properties
            WebDAV.CurrentUserPrivilegeSet,
            WebDAV.DisplayName,
            WebDAV.Owner,
            WebDAV.ResourceType,
            WebDAVPush.Transports,
            WebDAVPush.Topic
        ) + when (serviceType) {        // service-specific CalDAV/CardDAV properties
            Service.TYPE_CARDDAV -> arrayOf(
                CardDAV.AddressbookDescription
            )

            Service.TYPE_CALDAV -> arrayOf(
                CalDAV.CalendarColor,
                CalDAV.CalendarDescription,
                CalDAV.CalendarTimezone,
                CalDAV.CalendarTimezoneId,
                CalDAV.SupportedCalendarComponentSet,
                CalDAV.Source
            )

            else -> throw IllegalArgumentException()
        }

    /**
     * Finds out whether given collection is usable for synchronization, by checking that either
     *
     *  - CalDAV/CardDAV: service and collection type match, or
     *  - WebCal: subscription source URL is not empty.
     */
    fun isUsableCollection(service: Service, collection: Collection) =
        (service.type == Service.TYPE_CARDDAV && collection.type == Collection.TYPE_ADDRESSBOOK) ||
                (service.type == Service.TYPE_CALDAV && arrayOf(Collection.TYPE_CALENDAR, Collection.TYPE_WEBCAL).contains(collection.type)) ||
                (collection.type == Collection.TYPE_WEBCAL && collection.source != null)

}