package com.atnip.seizuretracker.testutil

import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf

/**
 * Collects [this] until [predicate] matches, or times out after [timeoutMs].
 *
 * Firestore's `addSnapshotListener` posts its callback to Android's main-thread `Handler` by
 * default (unlike the Play-Services `Task.await()` path used for one-shot reads/writes
 * elsewhere in these tests, which uses a direct executor and needs no help). Robolectric's main
 * Looper is paused (`LooperMode.PAUSED`, the only mode Robolectric 4.9+ supports) — posted
 * callbacks just sit in the queue until something explicitly idles it. A plain
 * `flow.first { predicate }` would hang forever waiting on a callback that never runs, so every
 * flow in these tests backed by a live Firestore listener (`observeHousehold`,
 * `observeSeizures`, and the ViewModel `StateFlow`s built on top of them) is awaited through
 * this helper instead of a bare `.first { }`, so the main looper actually gets pumped.
 */
suspend fun <T> Flow<T>.awaitFirst(timeoutMs: Long = 5000, predicate: (T) -> Boolean): T =
    withTimeout(timeoutMs) {
        var result: T? = null
        var matched = false
        val job = launch {
            collect { value ->
                if (!matched && predicate(value)) {
                    result = value
                    matched = true
                }
            }
        }
        while (!matched) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
        job.cancel()
        @Suppress("UNCHECKED_CAST")
        result as T
    }
