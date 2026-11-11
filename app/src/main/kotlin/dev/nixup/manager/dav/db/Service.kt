/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.db

import androidx.annotation.StringDef
import io.ktor.http.Url

@Retention(AnnotationRetention.SOURCE)
@StringDef(Service.TYPE_CALDAV, Service.TYPE_CARDDAV)
annotation class ServiceType

/**
 * A service entity.
 *
 * Services represent accounts and are unique. They are of type CardDAV or CalDAV and may have an associated principal.
 */
data class Service(
    val id: Long,

    // TODO: Reference accounts by their database ID once we've gotten rid of `LegacyAccount`.
    val accountName: String,

    @ServiceType
    val type: String,

    val principal: Url? = null
) {

    companion object {
        const val TYPE_CALDAV = "caldav"
        const val TYPE_CARDDAV = "carddav"
    }

}