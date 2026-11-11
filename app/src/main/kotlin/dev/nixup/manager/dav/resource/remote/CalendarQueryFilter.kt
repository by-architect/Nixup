/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.resource.remote

import java.time.Instant

/**
 * Restricts the members returned by [CalDavCollection.listFilteredMembers].
 */
data class CalendarQueryFilter(
    /** iCalendar component names to query (for instance `VEVENT`, `VTODO`) */
    val components: List<String>,

    /** if given, only components ending after this instant are returned */
    val timeRangeStart: Instant? = null,

    /** if given, only components starting before this instant are returned */
    val timeRangeEnd: Instant? = null
)
