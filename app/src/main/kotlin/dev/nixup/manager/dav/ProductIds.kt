/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its ProductIds, naming Nixup.
 */

package dev.nixup.manager.dav

import android.content.Context
import at.bitfire.synctools.icalendar.ical4jVersion
import ezvcard.Ezvcard

/**
 * How the sync names itself: to servers (User-Agent), and in the iCalendars and vCards it writes (PRODID). [context]
 * gives the app's version; there is none in the engine, which only looks at servers.
 */
class ProductIds(context: Context?) {

    private val versionName = context?.let {
        runCatching { it.packageManager.getPackageInfo(it.packageName, 0).versionName }.getOrNull()
    } ?: "unknown"

    val httpUserAgent = "Nixup/$versionName (DAVx5 sync; dev.nixup.manager)"

    val iCalProdId = "Nixup/$versionName ical4j/$ical4jVersion"
    val vCardProdId = "+//IDN nixup.dev//Nixup/$versionName ez-vcard/${Ezvcard.VERSION}"

}
