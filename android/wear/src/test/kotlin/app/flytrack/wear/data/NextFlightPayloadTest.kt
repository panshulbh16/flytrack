package app.flytrack.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NextFlightPayloadTest {

    @Test fun decodesWhatThePhoneWrites() {
        val payload = NextFlightPayload.decode(PHONE_JSON.encodeToByteArray())!!
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", payload.id)
        assertEquals("6E", payload.c)
        assertEquals("2341", payload.n)
        assertEquals("DEL", payload.dep)
        assertEquals("BOM", payload.arr)
        assertEquals("scheduled", payload.st)
        assertEquals("2026-11-25T12:55:00Z", payload.sd)
        assertEquals("42B", payload.dg)
        assertEquals("1", payload.dt)
        assertEquals("Asia/Kolkata", payload.dtz)
        assertEquals("on_time", payload.sw)
        assertEquals("on_time", payload.tone)
        assertNull(payload.ed)
        assertNull(payload.dm)
        assertFalse(payload.est)
    }

    @Test fun unknownKeysFromANewerPhoneAreIgnored() {
        val json = """{"v":1,"id":"x","c":"AI","n":"101","dep":"DEL","arr":"JFK","st":"departed","lat":28.5,"extra":{"a":1}}"""
        val payload = NextFlightPayload.decode(json.encodeToByteArray())!!
        assertEquals("AI", payload.c)
        assertEquals("departed", payload.st)
    }

    @Test fun anIncompatibleVersionIsNotShown() {
        val json = """{"v":2,"id":"x","c":"AI","n":"101","dep":"DEL","arr":"JFK"}"""
        assertNull(NextFlightPayload.decode(json.encodeToByteArray()))
    }

    @Test fun malformedOrMissingDataIsNull() {
        assertNull(NextFlightPayload.decode(null))
        assertNull(NextFlightPayload.decode(ByteArray(0)))
        assertNull(NextFlightPayload.decode("not json".encodeToByteArray()))
        assertNull(NextFlightPayload.decode("""{"v":1,"c":"AI"}""".encodeToByteArray()))
    }

    companion object {
        /** Exactly what :app's WearNextFlightTest.GOLDEN_JSON pins for the phone side. */
        const val PHONE_JSON =
            """{"v":1,"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","c":"6E","n":"2341","d":"2026-11-25","dep":"DEL","arr":"BOM",""" +
                """"st":"scheduled","sd":"2026-11-25T12:55:00Z","sa":"2026-11-25T15:10:00Z","dt":"1","dg":"42B","at":"2",""" +
                """"dtz":"Asia/Kolkata","atz":"Asia/Kolkata","sw":"on_time","tone":"on_time","est":false}"""
    }
}
