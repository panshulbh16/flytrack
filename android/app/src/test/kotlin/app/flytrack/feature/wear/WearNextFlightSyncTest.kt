package app.flytrack.feature.wear

import app.flytrack.testing.TestData
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The publisher over a fake Data Layer: what it writes, and that a failing Data Layer never escapes. */
class WearNextFlightSyncTest {

    private val now = Instant.parse("2026-11-24T10:00:00Z")

    private class FakeWriter : WearDataWriter {
        val puts = mutableListOf<WearNextFlight>()
        var deletes = 0
        var hang = false
        var fail: Exception? = null

        override suspend fun put(bytes: ByteArray) {
            if (hang) awaitCancellation()
            fail?.let { throw it }
            puts += WearNextFlight.decode(bytes)
        }

        override suspend fun delete() {
            if (hang) awaitCancellation()
            fail?.let { throw it }
            deletes++
        }
    }

    @Test fun writesOnceThenOnlyOnChangeAndDeletesWhenNothingIsLeft() = runTest {
        val writer = FakeWriter()
        val sync = WearNextFlightSync(writer)
        sync.publish(listOf(TestData.tracked()), now)
        sync.publish(listOf(TestData.tracked()), now)
        assertEquals(1, writer.puts.size)
        assertEquals(TestData.FLIGHT_ID, writer.puts.single().id)
        sync.publish(emptyList(), now)
        sync.publish(emptyList(), now)
        assertEquals(1, writer.deletes)
    }

    @Test fun aHangingDataLayerTimesOutWithoutThrowingAndIsRetried() = runTest {
        val writer = FakeWriter().apply { hang = true }
        val failures = mutableListOf<WearPublishFailure>()
        val sync = WearNextFlightSync(writer, timeout = 10.seconds, onFailure = { failures += it })
        // Must return normally: a TimeoutCancellationException escaping here would end the caller's
        // Room collector (LiveCoordinator) without a trace.
        sync.publish(listOf(TestData.tracked()), now)
        assertEquals(listOf(WearPublishFailure.TIMEOUT), failures)
        writer.hang = false
        sync.publish(listOf(TestData.tracked()), now)
        assertEquals(1, writer.puts.size)
    }

    @Test fun anErrorIsContainedAndRetried() = runTest {
        val writer = FakeWriter().apply { fail = IllegalStateException("boom") }
        val failures = mutableListOf<WearPublishFailure>()
        val sync = WearNextFlightSync(writer, onFailure = { failures += it })
        sync.publish(listOf(TestData.tracked()), now)
        assertEquals(listOf(WearPublishFailure.OTHER), failures)
        writer.fail = null
        sync.publish(listOf(TestData.tracked()), now)
        assertEquals(1, writer.puts.size)
    }

    @Test fun aMissingWearableApiStopsFurtherAttempts() = runTest {
        val writer = FakeWriter().apply { fail = IllegalStateException("no wear api") }
        val sync = WearNextFlightSync(writer, classify = { WearPublishFailure.API_UNAVAILABLE })
        assertTrue(sync.available)
        sync.publish(listOf(TestData.tracked()), now)
        assertFalse(sync.available)
        writer.fail = null
        sync.publish(listOf(TestData.tracked()), now)
        assertTrue(writer.puts.isEmpty())
    }
}
