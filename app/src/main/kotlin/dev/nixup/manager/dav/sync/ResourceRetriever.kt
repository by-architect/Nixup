/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.settings.AccountSettings
import dev.nixup.manager.dav.util.DavUtils.toURIorNull
import ezvcard.util.DataUri
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Downloads a separate resource that is referenced during synchronization, for instance in
 * a vCard with `PHOTO:<external URL>`.
 *
 * The [ResourceRetriever] only sends authentication for URLs on the same domain as the
 * original URL. For instance, if the vCard that references a photo is taken from
 * `example.com` ([originalHost]), then [retrieve] will send authentication
 * when downloading `https://example.com/photo.jpg`, but not for `https://external-hoster.com/photo.jpg`.
 *
 * @param accountId     [AccountId] of the account to build authentication from
 * @param originalHost  client only authenticates for the domain of this host
 */
class ResourceRetriever(
    private val accountId: AccountId,
    private val originalHost: String,
    private val httpClient: HttpClient? = null,
    private val logger: Logger = Dav.logger
) {

    /**
     * Retrieves the given resource and returns it as an in-memory blob.
     * Supports HTTP/HTTPS (→ will download) and data (→ will decode) URLs.
     *
     * Authentication is handled as described in [ResourceRetriever].
     *
     * @param url        URL of the resource to download (`http`, `https` or `data` scheme)
     *
     * @return blob of requested resource, or `null` on error or when the URL scheme is not supported
     */
    suspend fun retrieve(url: String): ByteArray? =
        try {
            when (url.toURIorNull()?.scheme?.lowercase()) {
                "data" ->
                    DataUri.parse(url).data

                "http", "https" -> {
                    val httpClient = httpClient ?: Dav.httpClientBuilder()
                        .fromAccount(AccountSettings(Dav.context, accountId), authDomain = originalHost)
                        .followRedirects(true)
                        .build()
                    val response = httpClient.get(url)
                    if (response.status.isSuccess())
                        response.bodyAsBytes()
                    else {
                        logger.warning("Couldn't download external resource (${response.status})")
                        null
                    }
                }

                else ->
                    null
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Couldn't retrieve resource", e)
            null
        }

}