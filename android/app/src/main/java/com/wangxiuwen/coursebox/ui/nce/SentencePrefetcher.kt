package com.wangxiuwen.coursebox.ui.nce

import android.util.Log
import com.wangxiuwen.coursebox.CourseboxApp
import com.wangxiuwen.coursebox.core.CourseLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pre-warms the VAD sentence cache for every course in the library, one
 * lesson at a time, so opening a lesson is instant instead of a live
 * analysis on a low-end tablet.
 *
 * Runs for the lifetime of the process and defers to the learner: while a
 * lesson is playing or a user-triggered analysis is in flight the queue
 * idles, resuming once the player goes quiet. A lesson that fails (corrupt
 * audio, codec starvation, process death) is simply left uncached — the
 * next launch picks it up again.
 */
object SentencePrefetcher {
    private const val TAG = "SentencePrefetch"
    private const val IDLE_POLL_MS = 4_000L
    private const val BETWEEN_LESSONS_MS = 1_500L

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Idempotent; later calls with a newer library snapshot are ignored —
     *  the queue re-reads [CourseLibrary.packages] on each pass anyway. */
    fun start(library: CourseLibrary) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val analyzer = VoiceActivityAnalyzer(CourseboxApp.instance)
            while (true) {
                var analysed = 0
                for (pkg in library.state.packages) {
                    val lessons = runCatching { loadNceLessons(library, pkg.id) }
                        .onFailure { Log.w(TAG, "manifest ${pkg.id} unreadable", it) }
                        .getOrDefault(emptyList())
                    // Audio lessons first — video objects decode far slower,
                    // so they must not delay the common case.
                    for (lesson in lessons.sortedBy { it.isVideo }) {
                        val mediaPath = lesson.resolveMediaPath(library) ?: continue
                        if (analyzer.hasCache(mediaPath)) continue
                        waitForPlayerQuiet()
                        val done = runCatching {
                            analyzer.analyze(mediaPath)
                        }.onFailure { Log.w(TAG, "prefetch ${pkg.id}/${lesson.id} failed", it) }
                            .isSuccess
                        if (done) analysed++
                        delay(BETWEEN_LESSONS_MS)
                    }
                }
                // Cache fully warm; look again later — imports and cache
                // clears land between passes.
                if (analysed > 0) Log.i(TAG, "pass done, $analysed lessons analysed")
                delay(60_000L)
            }
        }
    }

    /** Suspend until the learner's own playback and analysis are both idle.
     *  Checked repeatedly so a lesson started mid-prefetch reclaims the
     *  device within one poll interval. */
    private suspend fun waitForPlayerQuiet() {
        while (true) {
            val vm = CourseboxApp.playerVm
            val busy = vm.isPlaying || vm.sentenceAnalysisState == SentenceAnalysisState.ANALYZING
            if (!busy) return
            delay(IDLE_POLL_MS)
        }
    }
}
