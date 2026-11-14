/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.servicedetection

import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.db.DavCollectionRepository
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.resolve
import at.bitfire.dav4jvm.ktor.responses
import at.bitfire.dav4jvm.property.webdav.Owner
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.db.Service
import io.ktor.client.HttpClient

/**
 * Logic for refreshing the list of collections (and their related information)
 * which do not belong to a home set.
 */
class CollectionsWithoutHomeSetRefresher(
    private val service: Service,
    private val httpClient: HttpClient,
    private val collectionRepository: DavCollectionRepository = Dav.store.collectionRepository
) {

    /**
     * Refreshes collections which don't have a homeset.
     *
     * It queries each stored collection with a homeSetId of "null" and either updates or deletes (if inaccessible or unusable) them.
     */
    internal suspend fun refreshCollectionsWithoutHomeSet() {
        val withoutHomeSet =
            collectionRepository.getByServiceAndHomeset(service.id, null).associateBy { it.url }.toMutableMap()
        for ((url, localCollection) in withoutHomeSet) try {
            val collectionProperties = ServiceDetectionUtils.collectionQueryProperties(service.type)
            DavResource(httpClient, url).propfind(0, *collectionProperties).responses().collect { response ->
                if (!response.isSuccess()) {
                    collectionRepository.delete(localCollection)
                    return@collect
                }

                // Save or update the collection, if usable, otherwise delete it
                Collection.fromDavResponse(response)?.let { collection ->
                    if (!ServiceDetectionUtils.isUsableCollection(service, collection))
                        return@let
                    collectionRepository.insertOrUpdateByUrlRememberSync(collection.copy(
                        serviceId = localCollection.serviceId          // use same service ID as previous entry
                    ))
                } ?: collectionRepository.delete(localCollection)
            }
        } catch (e: HttpException) {
            // delete collection locally if it was not accessible (40x)
            if (e.statusCode in arrayOf(403, 404, 410))
                collectionRepository.delete(localCollection)
            else
                throw e
        }
    }

}
