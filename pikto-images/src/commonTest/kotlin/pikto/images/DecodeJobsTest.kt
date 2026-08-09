package pikto.images

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DecodeJobsTest {

    @Test
    fun `callers asking for the same key share one decode`() = runTest {
        var decodes = 0
        val gate = CompletableDeferred<Unit>()
        val jobs = DecodeJobs<String>(scope = backgroundScope, maxParallel = 2) {
            decodes++
            gate.await()
            null
        }

        val waiting = List(4) { async { jobs.load("photo") } }
        // Everyone is parked on the same decode before it is allowed to finish.
        advanceUntilIdle()
        gate.complete(Unit)
        waiting.awaitAll()

        assertEquals(1, decodes)
    }

    @Test
    fun `different keys decode independently`() = runTest {
        val decoded = mutableListOf<String>()
        val jobs = DecodeJobs<String>(scope = backgroundScope, maxParallel = 2) {
            decoded += it
            null
        }

        jobs.load("first")
        jobs.load("second")

        assertEquals(listOf("first", "second"), decoded)
    }

    /**
     * The point of the whole class: a prefetch window is re-issued on every scroll, and the item
     * about to appear must not have its decode restarted because the previous window went away.
     */
    @Test
    fun `a caller walking away leaves the decode running for the next one`() = runTest {
        var decodes = 0
        val gate = CompletableDeferred<Unit>()
        val jobs = DecodeJobs<String>(scope = backgroundScope, maxParallel = 2) {
            decodes++
            gate.await()
            null
        }

        val abandoned = async { jobs.load("photo") }
        advanceUntilIdle()
        abandoned.cancel()

        val rejoined = async { jobs.load("photo") }
        advanceUntilIdle()
        gate.complete(Unit)
        rejoined.await()

        assertEquals(1, decodes)
    }

    @Test
    fun `a finished decode is not remembered as in flight`() = runTest {
        var decodes = 0
        val jobs = DecodeJobs<String>(scope = backgroundScope, maxParallel = 2) {
            decodes++
            null
        }

        jobs.load("photo")
        jobs.load("photo")

        // No memory cache in here, so a second load after the first finished decodes again. This
        // pins down that DecodeJobs deduplicates concurrent work and nothing more.
        assertEquals(2, decodes)
    }
}
