/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import at.bitfire.dav4jvm.ktor.DavCalendar
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.ProductIds
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.resource.local.LocalJtxCollection
import dev.nixup.manager.dav.resource.local.LocalJtxObject
import dev.nixup.manager.dav.resource.local.LocalResource
import dev.nixup.manager.dav.resource.remote.CalDavCollection
import dev.nixup.manager.dav.resource.remote.WebDavCollection
import dev.nixup.manager.dav.util.DavUtils
import dev.nixup.manager.dav.util.DavUtils.extractFileName
import at.bitfire.synctools.exception.InvalidResourceException
import at.bitfire.synctools.icalendar.CalendarUidSplitter
import at.bitfire.synctools.icalendar.ICalendarGenerator
import at.bitfire.synctools.icalendar.ICalendarParser
import at.bitfire.synctools.mapping.jtx.JtxObjectBuilder
import at.bitfire.synctools.mapping.jtx.JtxObjectHandler
import at.bitfire.synctools.mapping.jtx.handler.AndroidAttachmentFetcher
import io.ktor.client.HttpClient
import io.ktor.http.content.TextContent
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.property.ProdId
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.util.logging.Level

class JtxSyncManager(
    accountId: AccountId,
    httpClient: HttpClient,
    syncResult: SyncResult,
    override val localCollection: LocalJtxCollection,
    collectionInfo: Collection,
    override val remoteCollection: CalDavCollection,
    resync: ResyncType?,
    settings: SyncSettings,
    private val productIds: ProductIds = Dav.productIds
) : SyncManager<LocalJtxObject>(
    accountId,
    httpClient,
    SyncDataType.TASKS,
    syncResult,
    collectionInfo,
    resync,
    settings
) {

    override suspend fun generateUpload(
        resource: LocalJtxObject,
        capabilities: WebDavCollection.Capabilities
    ): GeneratedResource {
        val localJtxObject = resource.jtxObjectAndExceptions
        logger.log(Level.FINE, "Preparing upload of icalobject #{0}: {1}", arrayOf(resource.id, localJtxObject))

        // Map jtx object to iCalendar (also generates UID, if necessary)
        val handler = JtxObjectHandler(
            prodId = ProdId(productIds.iCalProdId),
            attachmentFetcher = AndroidAttachmentFetcher(
                client = resource.collection.client,
                account = resource.collection.account
            )
        )
        val mappedJtxObjects = handler.mapToCalendarComponents(localJtxObject)

        // Persist UID if it was generated
        if (mappedJtxObjects.generatedUid) {
            resource.updateUid(mappedJtxObjects.uid)
        }

        // generate iCalendar and convert to request body
        val iCalWriter = StringWriter()
        ICalendarGenerator().write(mappedJtxObjects.associatedComponents, iCalWriter)
        val outgoingContent = TextContent(
            text = iCalWriter.toString(),
            contentType = DavCalendar.MIME_ICALENDAR_UTF8
        )

        return GeneratedResource(
            suggestedFileName = DavUtils.fileNameFromUid(mappedJtxObjects.uid, "ics"),
            content = outgoingContent
        )
    }

    override fun syncAlgorithm(capabilities: WebDavCollection.Capabilities) = SyncAlgorithm.PROPFIND_REPORT

    override suspend fun processDownload(result: WebDavCollection.MultiGetItem) {
        result.url.withExceptionContext {
            val fileName = extractFileName(result.url)
            try {
                processICalObject(fileName, result.eTag, result.scheduleTag, StringReader(result.content))
            } catch (e: InvalidResourceException) {
                logger.log(Level.WARNING, "Error while processing jtx object", e)
                notifyInvalidResource(e, fileName)
            }
        }
    }

    override suspend fun postProcess() {
        localCollection.updateLastSync()
    }

        internal suspend fun processICalObject(fileName: String, eTag: String, scheduleTag: String?, reader: Reader) {
        val calendar = ICalendarParser().parse(reader)

        val uidsAndJournals = CalendarUidSplitter<CalendarComponent>().associateByUid(calendar, Component.VJOURNAL)
        val uidsAndTasks = CalendarUidSplitter<CalendarComponent>().associateByUid(calendar, Component.VTODO)

        if (uidsAndJournals.size + uidsAndTasks.size != 1) {
            logger.warning("Received iCalendar with not exactly one UID; ignoring $fileName")
            return
        }

        val uidsAndComponents = uidsAndJournals.ifEmpty { uidsAndTasks }
        val component = uidsAndComponents.values.first()

        val jtxEntityAndExceptions = JtxObjectBuilder(
            collectionId = localCollection.jtxCollection.id,
            fileName = fileName,
            eTag = eTag,
            scheduleTag = scheduleTag,
            flags = LocalResource.FLAG_REMOTELY_PRESENT
        ).build(component)

        val local = localCollection.findByName(fileName)
        if (local != null) {
            local.withExceptionContext {
                logger.info("Updating $fileName in local jtx collection: $component")
                local.update(jtxEntityAndExceptions)
            }
        } else {
            logger.info("Adding $fileName to local jtx collection: $component")
            localCollection.add(jtxEntityAndExceptions)
        }
    }
}
