/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */
package dev.nixup.manager.dav.sync

import at.bitfire.synctools.vcard.GroupMethod

/**
 * Immutable snapshot of the [dev.nixup.manager.dav.settings.AccountSettings] that are relevant for a sync run.
 *
 * Should be created once at the start of a sync run instead of querying [dev.nixup.manager.dav.settings.AccountSettings]
 * again and again during the same sync. This way, settings can't change in the middle of
 * a sync run, and expensive/blocking [dev.nixup.manager.dav.settings.AccountSettings] access is reduced.
 */
data class SyncSettings(
    // AccountSettings (same order)
    val syncWifiOnly: Boolean,
    val syncWifiOnlySSIDs: List<String>?,
    val ignoreVpns: Boolean,
    val timeRangePastDays: Int?,
    val defaultAlarm: Int?,
    val eventColors: Boolean,
    val groupMethod: GroupMethod
)
