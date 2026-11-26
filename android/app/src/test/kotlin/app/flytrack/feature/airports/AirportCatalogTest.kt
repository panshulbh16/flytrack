package app.flytrack.feature.airports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirportCatalogTest {
    private val list = AirportList.parse(File("src/main/assets/airports.json").readText())

    @Test
    fun `the bundled catalog has the scheduled airports with curated cities`() {
        assertTrue(list.size > 3900)
        val blr = list["BLR"]!!
        assertEquals("Bengaluru", blr.city)
        assertEquals("India", blr.country)
        assertEquals("Asia/Kolkata", blr.tz)
        assertEquals(list.airports.sortedBy { it.iata }, list.airports)
        assertNull(list["XYZ"])
    }

    @Test
    fun `parse skips rows of the wrong shape`() {
        val parsed = AirportList.parse("""[["DEL","Indira Gandhi International Airport","Delhi","India",28.5,77.1,"Asia/Kolkata"],["XX"],["BOMB","x","y","z",1,2,"UTC"],"nope"]""")
        assertEquals(listOf("DEL"), parsed.airports.map { it.iata })
    }

    @Test
    fun `search puts the exact code first, then cities that start with the query`() {
        assertEquals("BOM", list.search("bom").first().iata)
        val delhi = list.search("Delhi")
        assertEquals("DEL", delhi.first().iata)
        assertTrue(list.search("bengal").any { it.iata == "BLR" })
        assertTrue("accents are ignored", list.search("sao paulo").any { it.country == "Brazil" })
        assertTrue(list.search("zzzzqq").isEmpty())
    }

    @Test
    fun `a blank query lists Indian airports by city`() {
        val blank = list.search("  ", limit = 10)
        assertEquals(10, blank.size)
        assertTrue(blank.all { it.country == "India" })
        assertEquals(blank.sortedBy { it.city }, blank)
    }

    @Test
    fun `indian airports come first within a rank`() {
        val hyd = list.search("Hyderabad")
        assertEquals("HYD", hyd.first().iata)
    }
}
