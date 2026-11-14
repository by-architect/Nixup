/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.flatMapMerge

/**
 * Splits this flow into batches and processes each batch with [process].
 *
 * Batches are processed concurrently, so the result order across batches isn't guaranteed.
 *
 * @param batchSize The size of each batch.
 * @param process A function that takes a batch (list of [I]) and returns a Flow of [O].
 *
 * @return A Flow of [O] resulting from applying [process] to each batch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <I, O> Flow<I>.batchMap(
    batchSize: Int,
    process: (List<I>) -> Flow<O>
): Flow<O> =
    chunked(batchSize).flatMapMerge { process(it) }
