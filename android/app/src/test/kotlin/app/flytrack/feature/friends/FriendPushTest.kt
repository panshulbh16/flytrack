package app.flytrack.feature.friends

import app.flytrack.core.model.AlertType
import app.flytrack.core.push.PushContract
import app.flytrack.push.IncomingPush
import app.flytrack.push.parseIncomingPush
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Friend alerts as `notify.ts` builds them (role = "friend", friend_name). */
class FriendPushTest {

    private fun alert(type: String, friend: Boolean = true, quiet: Boolean = false, name: String? = "Asha"): IncomingPush.Alert =
        parseIncomingPush(
            buildMap {
                put(PushContract.KEY_KIND, PushContract.KIND_ALERT)
                put(PushContract.KEY_FLIGHT_ID, TestData.FLIGHT_ID)
                put(PushContract.KEY_EVENT_ID, "42")
                put(PushContract.KEY_EVENT_TYPE, type)
                put(PushContract.KEY_TITLE, "Asha's flight landed")
                put(PushContract.KEY_BODY, "Asha's flight 6E 2341 has landed in Mumbai")
                if (friend) put(FriendPushContract.KEY_ROLE, FriendPushContract.ROLE_FRIEND)
                if (friend && name != null) put(FriendPushContract.KEY_FRIEND_NAME, name)
                if (quiet) put(PushContract.KEY_QUIET, PushContract.QUIET_TRUE)
            },
        ) as IncomingPush.Alert

    @Test fun friendAlertsCarryRoleAndName() {
        val landed = alert("LANDED")
        assertTrue(landed.isFriend)
        assertEquals("Asha", landed.friendName)
        assertEquals(AlertType.LANDED, landed.eventType)
        assertEquals("${TestData.FLIGHT_ID}:landed", landed.tag)
        val tracker = alert("LANDED", friend = false)
        assertFalse(tracker.isFriend)
        assertNull(tracker.friendName)
        assertNull(alert("LANDED", name = " ").friendName)
    }

    @Test fun onlyCancellationAndDiversionAreLoudForFriends() {
        assertEquals(PushContract.CHANNEL_CRITICAL, alert("CANCELLED").channelId)
        assertEquals(PushContract.CHANNEL_CRITICAL, alert("DIVERTED").channelId)
        assertEquals(PushContract.CHANNEL_CRITICAL, alert("DIVERTED_DEST").channelId)
        // DELAY is critical for the tracker, not for a friend.
        assertEquals(PushContract.CHANNEL_CRITICAL, alert("DELAY", friend = false).channelId)
        assertEquals(PushContract.CHANNEL_UPDATES, alert("DELAY").channelId)
        assertEquals(PushContract.CHANNEL_UPDATES, alert("DEPARTED").channelId)
        assertEquals(PushContract.CHANNEL_QUIET, alert("LANDED", quiet = true).channelId)
        assertTrue(alert("DELAY", quiet = true).quiet)
        assertEquals(PushContract.CHANNEL_CRITICAL, alert("CANCELLED", quiet = true).channelId)
    }

    /** The app's copy of the friend rules must match the backend's (friend-alerts.ts, notify.ts). */
    @Test fun matchesTheServer() {
        val shared = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "supabase/functions/_shared") }
            .firstOrNull { it.isDirectory } ?: return
        val friendAlerts = File(shared, "friend-alerts.ts").takeIf { it.isFile }?.readText() ?: return
        val critical = Regex("""FRIEND_CRITICAL[^=]*=\s*\[([^\]]*)]""").find(friendAlerts)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        assertEquals(FriendPushContract.CRITICAL_EVENT_TYPES, critical)
        val notify = File(shared, "notify.ts").readText()
        assertTrue(notify.contains("data.${FriendPushContract.KEY_ROLE} = \"${FriendPushContract.ROLE_FRIEND}\""))
        assertTrue(notify.contains("data.${FriendPushContract.KEY_FRIEND_NAME} ="))
    }
}
