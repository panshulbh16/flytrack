package app.flytrack.feature.share

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ShareTextTest {
    private val flight = ShareFlight("6E", "2341", "DEL", "BOM")

    /** The real templates from strings_share.xml, so a broken placeholder fails here and not on a phone. */
    private val strings: Map<String, String> by lazy {
        val file = listOf("src/main/res/values/strings_share.xml", "app/src/main/res/values/strings_share.xml")
            .map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    @Test
    fun `the message has the Hinglish line, then the English line with the link`() {
        val url = shareUrl("https://heygate.app/f/", "tok_abc")
        val text = shareMessage(strings.getValue("share_text_hinglish"), strings.getValue("share_text_english"), flight, url)
        assertEquals(
            "Meri flight live track karo: 6E 2341 DEL→BOM\nTrack my flight 6E 2341 DEL→BOM live: https://heygate.app/f/#tok_abc",
            text,
        )
    }

    @Test
    fun `the English template is the contract's sentence`() {
        val text = shareMessage("", strings.getValue("share_text_english"), ShareFlight("AI", "101", "BOM", "DXB"), "https://x.test/#t")
        assertEquals("Track my flight AI 101 BOM→DXB live: https://x.test/#t", text)
    }

    @Test
    fun `the link puts the token in the fragment`() {
        assertEquals("https://heygate.app/f/#abc", shareUrl("https://heygate.app/f/", "abc"))
        assertEquals("https://heygate.app/f/#abc", shareUrl("  ", "abc"))
        assertEquals("https://share.example/f#abc", shareUrl("https://share.example/f#old", " abc "))
        assertEquals(DEFAULT_SHARE_BASE_URL + "#t", shareUrl("", "t"))
    }

    @Test
    fun `WhatsApp is tried before WhatsApp Business`() {
        assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), WHATSAPP_PACKAGES)
    }
}
