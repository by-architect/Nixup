/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.db

import dev.nixup.manager.dav.util.DavUtils.extractCollectionName
import io.ktor.http.Url

data class HomeSet(
    val id: Long,

    val serviceId: Long,

    /**
     * Whether this homeset belongs to the [Service.principal] given by [serviceId].
     */
    val personal: Boolean,

    /**
     * URL of the home set - with trailing slash
     */
    val url: Url,

    val privBind: Boolean = true,

    val displayName: String? = null
) {
    init {
        require(url.encodedPath.endsWith('/')) { "Not a collection URL (path does not end with a slash): $url" }
    }

    fun title() = displayName ?: extractCollectionName(url)

}