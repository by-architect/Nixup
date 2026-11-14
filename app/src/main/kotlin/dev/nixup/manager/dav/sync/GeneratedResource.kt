/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import io.ktor.http.content.OutgoingContent

/**
 * Represents a resource that has been generated for the purpose of being uploaded.
 *
 * @param suggestedFileName     file name that can be used for uploading if there's no existing name
 * @param content               resource content (including MIME type)
 * @param onSuccessContext      context that must be passed to [SyncManager.onSuccessfulUpload]
 * on successful upload in order to persist the changes made during mapping
 */
class GeneratedResource(
    val suggestedFileName: String,
    val content: OutgoingContent,
    val onSuccessContext: OnSuccessContext? = null
) {

    /**
     * Contains information that has been created for a [GeneratedResource], but has not been saved yet.
     *
     * @param sequence  new SEQUENCE to persist on successful upload (*null*: SEQUENCE not modified)
     */
    data class OnSuccessContext(
        val sequence: Int? = null
    )

}