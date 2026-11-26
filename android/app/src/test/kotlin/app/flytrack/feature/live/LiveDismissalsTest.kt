package app.flytrack.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDismissalsTest {
    @Test fun remembersTheMostRecentDismissalsOnce() {
        assertEquals(listOf("a", "b"), rememberDismissal(listOf("b", "a"), "b"))
        val many = (1..MAX_REMEMBERED).map { "f$it" }
        val updated = rememberDismissal(many, "new")
        assertEquals(MAX_REMEMBERED, updated.size)
        assertEquals("new", updated.last())
        assertEquals("f2", updated.first())
    }
}
