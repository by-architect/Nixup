/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its HttpClientBuilder, with what Nixup's accounts use (a user
 * name and password: Basic or Digest authentication); no OAuth, client certificates nor proxy settings of its own (the
 * phone's are Android's).
 */

package dev.nixup.manager.dav.network

import at.bitfire.dav4jvm.ktor.DomainAuthProvider
import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import at.bitfire.dav4jvm.ktor.UrlUtils
import dev.nixup.manager.dav.ProductIds
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.settings.AccountSettings
import dev.nixup.manager.dav.settings.Credentials
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.util.appendIfNameAbsent
import okhttp3.ConnectionSpec
import java.util.Locale
import java.util.logging.Logger

class HttpClientBuilder private constructor(
    private val productIds: ProductIds,
    private val config: Config
) {

    constructor(productIds: ProductIds, logger: Logger) : this(productIds, Config(logger = logger))

    private data class Config(
        val logger: Logger,
        val authUsername: String? = null,
        val authPassword: String? = null,
        val authDomain: String? = null,
        val followRedirects: Boolean = false
    )

    private fun withConfig(update: (Config) -> Config) = HttpClientBuilder(productIds, update(config))

    /**
     * Authenticates with [credentials]' user name and password, only to the hosts of [domain] (all of them: null).
     */
    fun authenticate(domain: String?, credentials: Credentials): HttpClientBuilder = withConfig { config ->
        if (credentials.username != null && credentials.password != null)
            config.copy(authUsername = credentials.username, authPassword = credentials.password, authDomain = domain)
        else
            config
    }

    fun followRedirects(follow: Boolean): HttpClientBuilder = withConfig { it.copy(followRedirects = follow) }

    /** With [settings]' credentials (an account's), for the hosts of [authDomain] only, when given. */
    fun fromAccount(settings: AccountSettings, authDomain: String? = null): HttpClientBuilder =
        authenticate(domain = UrlUtils.hostToDomain(authDomain), credentials = settings.credentials())

    @Suppress("UNUSED_PARAMETER")
    fun fromAccount(accountId: AccountId, settings: AccountSettings, authDomain: String? = null): HttpClientBuilder =
        fromAccount(settings, authDomain)

    private fun HttpClientConfig<*>.installPlugins() {
        // don't follow redirects by default because it would break PROPFIND handling
        followRedirects = config.followRedirects

        val username = config.authUsername
        val password = config.authPassword
        if (username != null && password != null)
            install(Auth) {
                providers.add(
                    DomainAuthProvider(
                        config.authDomain,
                        PreemptiveBasicDigestAuthProvider(username, password)
                    )
                )
            }

        // Uses AcceptAllCookiesStorage, which stores all the cookies in an in-memory map.
        install(HttpCookies)

        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000       // covers both read and write inactivity
        }

        install(UserAgent) {
            agent = productIds.httpUserAgent
        }
        install(DefaultRequest) {
            val locale = Locale.getDefault()
            headers.appendIfNameAbsent(
                HttpHeaders.AcceptLanguage,
                "${locale.language}-${locale.country}, ${locale.language};q=0.7, *;q=0.5"
            )
        }

        // offer gzip/deflate/identity compression and decompress responses transparently
        ContentEncoding()
    }

    fun build(): HttpClient =
        HttpClient(OkHttp) {
            installPlugins()

            engine {
                config {
                    // Allow cleartext and TLS 1.2+
                    connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS))
                }
            }
        }

}
