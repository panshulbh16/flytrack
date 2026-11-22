package app.flytrack.ui.nav

import app.flytrack.core.push.PushContract
import app.flytrack.core.share.SharedContent
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.testing.Fixtures
import app.flytrack.testing.TestData
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchRequestTest {
    @Test
    fun `tapped alert notification opens its flight`() {
        // The system delivers the FCM data keys as extras together with the click_action.
        val alert = FlyTrackJson.parseToJsonElement(Fixtures.read("fcm_messages.json")).jsonObject
        val flightId = alert.getValue("alert").jsonObject.getValue(PushContract.KEY_FLIGHT_ID).jsonPrimitive.content
        val clickAction = alert.getValue("alertNotification").jsonObject.getValue("click_action").jsonPrimitive.content

        val request = resolveLaunchRequest(IncomingIntent(action = clickAction, flightId = flightId))

        assertEquals(PushContract.ACTION_OPEN_FLIGHT, clickAction)
        assertEquals(LaunchRequest.OpenFlight(TestData.FLIGHT_ID), request)
    }

    @Test
    fun `flight id extra opens the flight even on a plain launcher intent`() {
        val request = resolveLaunchRequest(IncomingIntent(action = "android.intent.action.MAIN", flightId = TestData.FLIGHT_ID))
        assertEquals(LaunchRequest.OpenFlight(TestData.FLIGHT_ID), request)
    }

    @Test
    fun `a flight id that is not a UUID is ignored`() {
        assertNull(resolveLaunchRequest(IncomingIntent(action = PushContract.ACTION_OPEN_FLIGHT, flightId = "../../etc")))
        assertNull(resolveLaunchRequest(IncomingIntent(action = PushContract.ACTION_OPEN_FLIGHT, flightId = null)))
        assertNull(resolveLaunchRequest(IncomingIntent(action = PushContract.ACTION_OPEN_FLIGHT, flightId = "")))
    }

    @Test
    fun `plain launch asks for nothing`() {
        assertNull(resolveLaunchRequest(IncomingIntent(action = "android.intent.action.MAIN")))
        assertNull(resolveLaunchRequest(IncomingIntent()))
    }

    @Test
    fun `shared pdf goes to the importer`() {
        val request = resolveLaunchRequest(
            IncomingIntent(action = ACTION_SEND, mimeType = "application/pdf", streamUris = listOf("content://mail/1")),
        )
        assertEquals(
            LaunchRequest.ImportShared(SharedContent(uris = listOf("content://mail/1"), mimeType = "application/pdf")),
            request,
        )
    }

    @Test
    fun `shared screenshots go to the importer`() {
        val uris = listOf("content://media/1", "content://media/2")
        val request = resolveLaunchRequest(IncomingIntent(action = ACTION_SEND_MULTIPLE, mimeType = "image/*", streamUris = uris))
        assertEquals(LaunchRequest.ImportShared(SharedContent(uris = uris, mimeType = "image/*")), request)
    }

    @Test
    fun `shared text goes to the importer`() {
        val request = resolveLaunchRequest(IncomingIntent(action = ACTION_SEND, mimeType = "text/plain", text = "Your PNR is ABC123"))
        assertEquals(LaunchRequest.ImportShared(SharedContent(mimeType = "text/plain", text = "Your PNR is ABC123")), request)
    }

    @Test
    fun `shares without usable content or of other types are ignored`() {
        assertNull(resolveLaunchRequest(IncomingIntent(action = ACTION_SEND, mimeType = "text/plain", text = "  ")))
        assertNull(resolveLaunchRequest(IncomingIntent(action = ACTION_SEND, mimeType = "application/pdf")))
        assertNull(resolveLaunchRequest(IncomingIntent(action = ACTION_SEND, mimeType = "video/mp4", streamUris = listOf("content://v/1"))))
        assertNull(resolveLaunchRequest(IncomingIntent(action = "android.intent.action.VIEW", mimeType = "application/pdf", streamUris = listOf("content://x"))))
    }
}
