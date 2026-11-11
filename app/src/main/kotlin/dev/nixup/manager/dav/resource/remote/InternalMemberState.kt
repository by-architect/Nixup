/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.resource.remote

import dev.nixup.manager.dav.util.DavUtils.extractFileName
import io.ktor.http.Url

/**
 * State of an internal member ("direct child") that is not a collection of a remote collection.
 *
 * @param href  URL of the member
 * @param eTag  current ETag of the member; `null` if the server didn't send one (violates RFC 4791 5.3.4)
 */
data class InternalMemberState(
    val href: Url,
    val eTag: String?
) {

    /**
     * Decoded (unescaped) file name of the member, i.e. the last path segment of [href] with
     * percent-encoding resolved.
     *
     * **Attention:** Because it's decoded, it may contain characters like `/`  that would
     * otherwise be path separators.
     */
    val fileName: String
        get() = extractFileName(href)

}
