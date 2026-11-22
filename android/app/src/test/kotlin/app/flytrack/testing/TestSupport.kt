package app.flytrack.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A clock tests can set and advance. Starts at [TestData.NOW]. */
class FakeClock(var current: Instant = TestData.NOW) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }
}

/**
 * Replaces Dispatchers.Main for ViewModel tests (viewModelScope needs it on the JVM):
 *
 *     @get:Rule val mainDispatcher = MainDispatcherRule()
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** JSON fixtures in src/test/resources/fixtures, generated from the real schema and Edge Function code. */
object Fixtures {
    fun read(name: String): String =
        checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }
}
