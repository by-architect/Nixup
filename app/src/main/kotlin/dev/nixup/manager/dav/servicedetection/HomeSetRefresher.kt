/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.servicedetection

import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.db.DavCollectionRepository
import dev.nixup.manager.dav.db.DavHomeSetRepository
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.resolve
import at.bitfire.dav4jvm.ktor.responsesWithRelation
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrivilegeSet
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.Owner
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.db.HomeSet
import dev.nixup.manager.dav.db.Service
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.filter
import java.util.logging.Logger

/**
 * Used to update the list of synchronizable collections
 */
class HomeSetRefresher(
    private val service: Service,
    private val httpClient: HttpClient,
    private val logger: Logger = Dav.logger,
    private val collectionRepository: DavCollectionRepository = Dav.store.collectionRepository,
    private val homeSetRepository: DavHomeSetRepository = Dav.store.homeSetRepository
) {

    /**
     * Refreshes home-sets and their collections.
     *
     * Each stored home-set URL is queried (`PROPFIND`) and its collections are either saved, updated
     * or marked as "without home-set" - in case a collection was removed from its home-set.
     *
     * If a home-set URL in fact points to a collection directly, the collection will be saved with this URL,
     * and a null value for it's home-set. Refreshing of collections without home-sets is then handled by [CollectionsWithoutHomeSetRefresher.refreshCollectionsWithoutHomeSet].
     */
    internal suspend fun refreshHomesetsAndTheirCollections() {
        val homesets = homeSetRepository.getByService(service.id).associateBy { it.url }.toMutableMap()
        for ((homeSetUrl, localHomeset) in homesets) {
            logger.fine("Listing home set $homeSetUrl")

            // To find removed collections in this homeset: create a queue from existing collections and remove every collection that
            // is successfully rediscovered. If there are collections left, after processing is done, these are marked as "without home-set".
            val localHomesetCollections = collectionRepository
                .getByServiceAndHomeset(service.id, localHomeset.id)
                .associateBy { it.url }
                .toMutableMap()

            try {
                val collectionProperties = ServiceDetectionUtils.collectionQueryProperties(service.type)
                DavResource(httpClient, homeSetUrl).propfind(1, *collectionProperties)
                    .responsesWithRelation()
                    .filter { (response, _) -> response.isSuccess() }
                    .collect { (response, relation) ->
                        if (relation == Response.HrefRelation.SELF) {
                            // this response is about the home set itself
                            homeSetRepository.insertOrUpdateByUrl(
                                localHomeset.copy(
                                    displayName = response[DisplayName::class.java]?.displayName,
                                    privBind = response[CurrentUserPrivilegeSet::class.java]?.mayBind != false
                                )
                            )
                        }

                        // in any case, check whether the response is about a usable collection
                        var collection = Collection.fromDavResponse(response) ?: return@collect
                        // (Whether it is synced is the config's: DavCollectionRepository.applySelection, after the
                        // lookup. Nixup keeps no principals of the owners.)
                        collection = collection.copy(
                            serviceId = service.id,
                            homeSetId = localHomeset.id,
                            sync = false
                        )
                        logger.fine("Found collection: $collection")

                        // save or update collection if usable (ignore it otherwise)
                        if (ServiceDetectionUtils.isUsableCollection(service, collection))
                            collectionRepository.insertOrUpdateByUrlRememberSync(collection)

                        // Remove this collection from queue - because it was found in the home set
                        localHomesetCollections.remove(collection.url)
                    }
            } catch (e: HttpException) {
                // delete home set locally if it was not accessible (40x)
                if (e.statusCode in arrayOf(403, 404, 410))
                    homeSetRepository.delete(localHomeset)
            }

            // Mark leftover (not rediscovered) collections from queue as "without home-set" (remove association)
            for ((_, collection) in localHomesetCollections)
                collectionRepository.insertOrUpdateByUrlRememberSync(
                    collection.copy(homeSetId = null)
                )

        }
    }

}
