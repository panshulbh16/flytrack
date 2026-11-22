package app.flytrack.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedContentHolderTest {
    private val pdf = SharedContent(uris = listOf("content://mail/attachment/1"), mimeType = "application/pdf")

    @Test
    fun `offered content is consumed exactly once`() {
        val holder = SharedContentHolder()
        holder.offer(pdf)
        assertEquals(pdf, holder.pending.value)
        assertEquals(pdf, holder.consume())
        assertNull(holder.consume())
        assertNull(holder.pending.value)
    }

    @Test
    fun `a newer share replaces an unconsumed one`() {
        val holder = SharedContentHolder()
        val text = SharedContent(text = "PNR ABC123 6E 2341", mimeType = "text/plain")
        holder.offer(pdf)
        holder.offer(text)
        assertEquals(text, holder.consume())
    }

    @Test
    fun `empty shares are ignored`() {
        val holder = SharedContentHolder()
        holder.offer(SharedContent(text = "   "))
        assertNull(holder.pending.value)
        assertTrue(SharedContent().isEmpty)
    }
}
