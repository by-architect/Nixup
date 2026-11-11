/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.resource.remote

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments

/**
 * Returns the URL of a member (non-collection resource) with the given file name inside this collection URL.
 *
 * [fileName] is treated as a single, unencoded (decoded) file name — it's percent-encoded when appended,
 * including a literal `/` (which would otherwise be interpreted as an additional path segment).
 *
 * @param fileName unencoded file name of the member
 *
 * @return URL of the member
 */
internal fun Url.member(fileName: String): Url =
    URLBuilder(this).appendPathSegments(fileName, encodeSlash = true).build()
