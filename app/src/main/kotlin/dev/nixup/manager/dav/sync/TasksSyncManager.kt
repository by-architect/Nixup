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
import dev.nixup.manager.dav.resource.local.LocalResource
import dev.nixup.manager.dav.resource.local.LocalTask
import dev.nixup.manager.dav.resource.local.LocalTaskList
import dev.nixup.manager.dav.resource.remote.CalDavCollection
import dev.nixup.manager.dav.resource.remote.WebDavCollection
import dev.nixup.manager.dav.util.DavUtils
import dev.nixup.manager.dav.util.DavUtils.extractFileName
import at.bitfire.synctools.exception.InvalidResourceException
import at.bitfire.synctools.icalendar.AssociatedTasks
import at.bitfire.synctools.icalendar.CalendarUidSplitter
import at.bitfire.synctools.icalendar.ICalendarGenerator
import at.bitfire.synctools.icalendar.ICalendarParser
import at.bitfire.synctools.mapping.tasks.DmfsTaskBuilder
import at.bitfire.synctools.mapping.tasks.DmfsTaskHandler
import at.bitfire.synctools.mapping.tasks.SequenceUpdater
import io.ktor.client.HttpClient
import io.ktor.http.content.TextContent
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.property.ProdId
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.util.logging.Level

/**
 * Synchronization manager for CalDAV collections; handles tasks (VTODO)
 */
class TasksSyncManager(
    accountId: AccountId,
    httpClient: HttpClient,
    syncResult: SyncResult,
    override val localCollection: LocalTaskList,
    collectionInfo: Collection,
    override val remoteCollection: CalDavCollection,
    resync: ResyncType?,
    settings: SyncSettings,
    private val productIds: ProductIds = Dav.productIds
) : SyncManager<LocalTask>(
    accountId,
    httpClient,
    SyncDataType.TASKS,
    syncResult,
    collectionInfo,
    resync,
    settings
) {

    override fun syncAlgorithm(capabilities: WebDavCollection.Capabilities) = SyncAlgorithm.PROPFIND_REPORT

    override suspend fun generateUpload(resource: LocalTask, capabilities: WebDavCollection.Capabilities): GeneratedResource {
        val localTask = resource.taskAndExceptions
        logger.log(Level.FINE, "Preparing upload of task #{0}: {1}", arrayOf(resource.id, localTask))

        /* Increase SEQUENCE of main task in memory and remember new value.
        Will be written to provider later over onSuccessContext. */
        val updatedSequence = SequenceUpdater().increaseSequence(localTask.main)

        // map Android event to iCalendar (also generates UID, if necessary)
        val handler = DmfsTaskHandler(
            prodId = ProdId(productIds.iCalProdId),
            providerName = localCollection.dmfsTaskList.providerName
        )
        val mappedVToDos = handler.mapToVToDos(localTask)

        // persist UID if it was generated
        if (mappedVToDos.generatedUid)
            resource.updateUid(mappedVToDos.uid)

        // generate iCalendar and convert to request body
        val iCalWriter = StringWriter()
        ICalendarGenerator().write(mappedVToDos.associatedTasks, iCalWriter)
        val outgoingContent = TextContent(
            text = iCalWriter.toString(),
            contentType = DavCalendar.MIME_ICALENDAR_UTF8
        )

        return GeneratedResource(
            suggestedFileName = DavUtils.fileNameFromUid(mappedVToDos.uid, "ics"),
            content = outgoingContent,
            onSuccessContext = GeneratedResource.OnSuccessContext(
                sequence = updatedSequence
            )
        )
    }

    override suspend fun processDownload(result: WebDavCollection.MultiGetItem) {
        result.url.withExceptionContext {
            val fileName = extractFileName(result.url)
            try {
                processVTodo(fileName, result.eTag, StringReader(result.content))
            } catch (e: InvalidResourceException) {
                logger.log(Level.WARNING, "Error while processing VTODO", e)
                notifyInvalidResource(e, fileName)
            }
        }
    }

    override suspend fun postProcess() {
        val touched = localCollection.dmfsTaskList.touchRelations()
        logger.info("Touched $touched relations")
    }


    // helpers

    private suspend fun processVTodo(fileName: String, eTag: String, reader: Reader) {
        val calendar = ICalendarParser().parse(reader)

        val uidsAndTasks = CalendarUidSplitter<VToDo>().associateByUid(calendar, Component.VTODO)
        if (uidsAndTasks.size != 1) {
            logger.warning("Received iCalendar with not exactly one UID; ignoring $fileName")
            return
        }
        // Task: main VTODO and potentially attached exceptions (further VTODOs with RECURRENCE-ID)
        val task: AssociatedTasks = uidsAndTasks.values.first()

        // map AssociatedTasks (VTODOs) to TaskAndExceptions (task provider tasks)
        val dmfsTask = DmfsTaskBuilder(
            taskList = localCollection.dmfsTaskList,
            syncId = fileName,
            eTag = eTag,
            flags = LocalResource.FLAG_REMOTELY_PRESENT
        ).build(task)

        // update local task, if it exists
        val local = localCollection.findByName(fileName)
        if (local != null) {
            local.withExceptionContext {
                logger.info("Updating $fileName in local task list: $task")
                local.update(dmfsTask)
            }
        } else {
            logger.info("Adding $fileName to local task list: $task")
            localCollection.add(dmfsTask)
        }
    }

}