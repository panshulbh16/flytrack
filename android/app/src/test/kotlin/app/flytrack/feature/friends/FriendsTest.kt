package app.flytrack.feature.friends

import app.flytrack.core.model.FlightStatus
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.share.ShareTarget
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class FriendsTest {
    @get:Rule val main = MainDispatcherRule()

    // ---- invite codes ----------------------------------------------------------------------------

    @Test fun normalizesCodes() {
        assertEquals("ABCD2345", normalizeInviteCode("abcd-2345"))
        assertEquals("ABCD2345", normalizeInviteCode(" ABCD 2345 "))
        assertNull(normalizeInviteCode("ABCD234"))      // too short
        assertNull(normalizeInviteCode("ABCD23450"))    // 0 is not in the alphabet
        assertNull(normalizeInviteCode("ABCDI234"))     // nor I
        assertNull(normalizeInviteCode("ABCD23456"))    // too long
    }

    @Test fun findsTheCodeInAPastedMessage() {
        val message = "HeyGate pe mujhe add karo, meri flights live dekho. Code: WXYZ-7K9P\n" +
            "Add me on HeyGate to follow my flights: Settings → Friends → Enter a code: WXYZ-7K9P"
        assertEquals("WXYZ7K9P", extractInviteCode(message))
        assertEquals("WXYZ7K9P", extractInviteCode("my code is wxyz7k9p ok"))
        assertEquals("QRSTUVWX", extractInviteCode("qrst-uvwx"))
        // Ordinary 8-letter words are not codes.
        assertNull(extractInviteCode("TRAVELER and STARTUPS"))
        assertNull(extractInviteCode(""))
    }

    @Test fun formatsCodesForReading() {
        assertEquals("ABCD-2345", formatInviteCode("ABCD2345"))
        assertEquals("ABC", formatInviteCode("ABC"))
    }

    @Test fun inviteMessageHasBothLines() {
        val text = inviteMessage("Code: %1\$s", "Enter %1\$s", "ABCD-2345")
        assertEquals("Code: ABCD-2345\nEnter ABCD-2345", text)
    }

    // ---- parsing ---------------------------------------------------------------------------------

    @Test fun parsesMyFriends() {
        val json = Json.parseToJsonElement(
            """[{"friend_id":"f1","display_name":"  Asha ","alerts_enabled":true,"since":"2026-11-20T10:00:00+00:00"},
                {"friend_id":"f2","display_name":null,"alerts_enabled":false,"since":"2026-11-19T10:00:00Z","extra":1}]""",
        )
        val friends = parseFriends(json)
        assertEquals(2, friends.size)
        assertEquals(Friend("f1", "Asha", true, Instant.parse("2026-11-20T10:00:00Z")), friends[0])
        assertNull(friends[1].displayName)
        assertFalse(friends[1].alertsEnabled)
        assertTrue(parseFriends(Json.parseToJsonElement("[]")).isEmpty())
    }

    @Test fun parsesFriendsFlightsIntoFlights() {
        val flights = parseFriendsFlights(Json.parseToJsonElement(FRIENDS_FLIGHTS))
        assertEquals(1, flights.size) // the row without a flight id is skipped
        val ff = flights.single()
        assertEquals("f1", ff.friendId)
        assertEquals("Asha", ff.friendName)
        val f = ff.flight
        assertEquals(TestData.FLIGHT_ID, f.id)
        assertEquals("6E 2341", f.designator)
        assertEquals("DEL", f.depIata)
        assertEquals("BOM", f.arrIata)
        assertEquals(FlightStatus.EN_ROUTE, f.status)
        assertEquals(Instant.parse("2026-11-25T13:05:00Z"), f.actDep)
        assertEquals("Asia/Kolkata", f.depTz)
        assertEquals("New Delhi", f.depAirport?.city)
        assertEquals("1", f.depTerminal)
        assertEquals("7", f.baggageBelt)
        assertEquals(19.5, f.live?.lat)
        assertNull(f.prediction)
    }

    @Test fun parsesAcceptAnswerAndText() {
        assertEquals(AcceptedFriend("f9", null), parseAcceptedFriend(Json.parseToJsonElement("""{"friend_id":"f9","display_name":""}""")))
        assertEquals("ABCD2345", textAnswer(JsonPrimitive("ABCD2345")))
        assertNull(textAnswer(JsonPrimitive("  ")))
        assertNull(textAnswer(JsonPrimitive(5)))
    }

    @Test fun mapsAcceptErrors() {
        assertEquals(AcceptInviteResult.InvalidCode, acceptError("P0002", "invalid_code"))
        assertEquals(AcceptInviteResult.OwnCode, acceptError("22023", "own_code"))
        assertEquals(AcceptInviteResult.AlreadyFriends, acceptError("23505", "already_friends"))
        assertEquals(AcceptInviteResult.FriendLimit, acceptError("P0001", "friend_limit"))
        assertEquals(AcceptInviteResult.FriendLimit, acceptError(null, "friend_limit"))
        assertTrue(acceptError("42501", "denied") is AcceptInviteResult.Failed)
        // Some other plpgsql error (default P0001) is not the friend limit.
        assertTrue(acceptError("P0001", "statement timeout in trigger") is AcceptInviteResult.Failed)
        assertEquals(AcceptInviteResult.FriendLimit, acceptError("P0001", null))
    }

    // ---- repository ------------------------------------------------------------------------------

    @Test fun repositoryCallsContractRpcs() = runTest {
        val api = FakeWave2Api()
        val repo = SupabaseFriendsRepository(caller(api))
        api.answers["create_friend_invite"] = JsonPrimitive("ABCD2345")
        api.answers["accept_friend_invite"] = Json.parseToJsonElement("""{"friend_id":"f1","display_name":"Asha"}""")

        assertEquals(RemoteResult.Ok("ABCD2345"), repo.createInvite())
        assertEquals(AcceptInviteResult.Accepted(AcceptedFriend("f1", "Asha")), repo.acceptInvite("abcd-2345"))
        assertEquals(RemoteResult.Ok(Unit), repo.setFriendAlerts("f1", false))
        assertEquals(RemoteResult.Ok(Unit), repo.removeFriend("f1"))
        assertEquals(RemoteResult.Ok(emptyList<Friend>()), repo.friends()) // SQL null answer

        assertEquals(
            listOf("create_friend_invite", "accept_friend_invite", "set_friend_alerts", "remove_friend", "my_friends"),
            api.calls.map { it.first },
        )
        assertEquals("ABCD2345", api.calls[1].second.getValue("p_code").jsonPrimitive.content)
        assertEquals("f1", api.calls[2].second.getValue("p_friend").jsonPrimitive.content)
        assertFalse(api.calls[2].second.getValue("p_enabled").jsonPrimitive.boolean)
    }

    @Test fun acceptRejectsNonCodesWithoutACall() = runTest {
        val api = FakeWave2Api()
        assertEquals(AcceptInviteResult.InvalidCode, SupabaseFriendsRepository(caller(api)).acceptInvite("hello"))
        assertTrue(api.calls.isEmpty())
    }

    @Test fun repositoryMapsErrors() = runTest {
        val api = FakeWave2Api()
        val repo = SupabaseFriendsRepository(caller(api))
        api.answers["accept_friend_invite"] = BackendException.Http(400, "23505", "already_friends")
        assertEquals(AcceptInviteResult.AlreadyFriends, repo.acceptInvite("ABCD2345"))
        api.answers["accept_friend_invite"] = BackendException.Network(java.io.IOException("down"))
        assertEquals(AcceptInviteResult.Offline, repo.acceptInvite("ABCD2345"))
        api.answers["my_friends"] = BackendException.Malformed(IllegalStateException())
        assertTrue(repo.friends() is RemoteResult.Failed)
        api.answers["create_friend_invite"] = JsonPrimitive("")
        assertTrue(repo.createInvite() is RemoteResult.Failed)
    }

    @Test fun signInProblemsComeFirst() = runTest {
        val api = FakeWave2Api()
        val notConfigured = FakeAuthRepository(SessionState.NotConfigured)
        assertEquals(RemoteResult.NotConfigured, SupabaseFriendsRepository(caller(api, notConfigured)).friends())
        val offline = FakeAuthRepository().apply { nextResult = AuthResult.Offline }
        assertEquals(AcceptInviteResult.Offline, SupabaseFriendsRepository(caller(api, offline)).acceptInvite("ABCD2345"))
        assertTrue(api.calls.isEmpty())
    }

    @Test fun shareWithFriendsReadsAndWritesOwnRow() = runTest {
        val api = FakeWave2Api().apply { shares[TestData.FLIGHT_ID] = true }
        val repo = SupabaseFriendsRepository(caller(api))
        assertEquals(RemoteResult.Ok(true), repo.shareWithFriends(TestData.FLIGHT_ID))
        assertEquals(RemoteResult.Ok(false), repo.setShareWithFriends(TestData.FLIGHT_ID, false))
        assertEquals(RemoteResult.Ok(null), repo.shareWithFriends(TestData.OTHER_FLIGHT_ID))
    }

    // ---- view models -----------------------------------------------------------------------------

    @Test fun friendsScreenLoadsInvitesAndRemembersTheCode() = runTest {
        val api = FakeWave2Api().apply {
            answers["my_friends"] = Json.parseToJsonElement("""[{"friend_id":"f1","display_name":"Asha","alerts_enabled":true}]""")
            answers["create_friend_invite"] = JsonPrimitive("ABCD2345")
        }
        val vm = FriendsViewModel(SupabaseFriendsRepository(caller(api)))
        assertEquals(FriendsLoad.LOADED, vm.uiState.value.load)
        assertEquals(listOf("f1"), vm.uiState.value.friends.map { it.id })

        vm.invite(ShareTarget.WHATSAPP_FIRST)
        val request = vm.uiState.value.inviteShare!!
        assertEquals(InviteShareRequest("ABCD2345", ShareTarget.WHATSAPP_FIRST), request)
        vm.inviteHandled(request)
        assertNull(vm.uiState.value.inviteShare)

        // The code is reused: no second RPC.
        vm.invite(null)
        assertEquals(InviteShareRequest("ABCD2345", null), vm.uiState.value.inviteShare)
        assertEquals(1, api.calls.count { it.first == "create_friend_invite" })
    }

    @Test fun alertSwitchGoesBackWhenTheServerRefuses() = runTest {
        val api = FakeWave2Api().apply {
            answers["my_friends"] = Json.parseToJsonElement("""[{"friend_id":"f1","alerts_enabled":true}]""")
            answers["set_friend_alerts"] = BackendException.Http(400, "P0002", "not_friends")
        }
        val vm = FriendsViewModel(SupabaseFriendsRepository(caller(api)))
        vm.setAlerts("f1", false)
        assertTrue(vm.uiState.value.friends.single().alertsEnabled)
        assertEquals(FriendsMessage.FAILED, vm.uiState.value.message)
        assertTrue(vm.uiState.value.busy.isEmpty())

        api.answers.remove("set_friend_alerts")
        vm.setAlerts("f1", false)
        assertFalse(vm.uiState.value.friends.single().alertsEnabled)
    }

    @Test fun removeDropsTheFriend() = runTest {
        val api = FakeWave2Api().apply {
            answers["my_friends"] = Json.parseToJsonElement("""[{"friend_id":"f1"},{"friend_id":"f2"}]""")
        }
        val vm = FriendsViewModel(SupabaseFriendsRepository(caller(api)))
        vm.remove("f1")
        assertEquals(listOf("f2"), vm.uiState.value.friends.map { it.id })
        assertEquals(FriendsMessage.REMOVED, vm.uiState.value.message)
    }

    @Test fun friendsScreenOffline() = runTest {
        val api = FakeWave2Api().apply { answers["my_friends"] = BackendException.Network(java.io.IOException()) }
        val vm = FriendsViewModel(SupabaseFriendsRepository(caller(api)))
        assertEquals(FriendsLoad.OFFLINE, vm.uiState.value.load)
    }

    @Test fun acceptScreenShrinksPastedMessagesAndReportsErrors() = runTest {
        val api = FakeWave2Api().apply { answers["accept_friend_invite"] = BackendException.Http(400, "22023", "own_code") }
        val vm = AcceptInviteViewModel(SupabaseFriendsRepository(caller(api)))
        assertFalse(vm.uiState.value.canSubmit)
        vm.onInput("Code: wxyz-7k9p\nAdd me on HeyGate")
        assertEquals("WXYZ-7K9P", vm.uiState.value.input)
        assertTrue(vm.uiState.value.canSubmit)
        vm.submit()
        assertEquals(AcceptError.OWN_CODE, vm.uiState.value.error)

        api.answers["accept_friend_invite"] = Json.parseToJsonElement("""{"friend_id":"f1","display_name":"Asha"}""")
        vm.onInput("WXYZ-7K9P")
        assertNull(vm.uiState.value.error)
        vm.submit()
        assertEquals(AcceptedFriend("f1", "Asha"), vm.uiState.value.accepted)
        assertFalse(vm.uiState.value.canSubmit)
    }

    @Test fun acceptScreenRejectsGarbageLocally() = runTest {
        val api = FakeWave2Api()
        val vm = AcceptInviteViewModel(SupabaseFriendsRepository(caller(api)))
        vm.onInput("abc")
        vm.submit()
        assertEquals(AcceptError.INVALID_CODE, vm.uiState.value.error)
        assertTrue(api.calls.isEmpty())
        vm.prefill("ZZZZ2222")
        assertEquals("ABC", vm.uiState.value.input) // prefill never overwrites typing
    }

    // ---- friends' flights ------------------------------------------------------------------------

    @Test fun friendsFlightsDropLongLandedOnes() {
        val now = Instant.parse("2026-11-25T20:00:00Z")
        val landedLongAgo = FriendFlight("f1", "Asha", TestData.flight(status = FlightStatus.LANDED, actArr = now - 7.hours))
        val landedJustNow = FriendFlight("f2", null, TestData.flight(id = TestData.OTHER_FLIGHT_ID, status = FlightStatus.LANDED, actArr = now - 1.hours))
        val cards = friendFlightCards(listOf(landedLongAgo, landedJustNow), now)
        assertEquals(listOf("f2"), cards.map { it.friendId })
        assertEquals(TestData.OTHER_FLIGHT_ID, cards.single().card.flightId)
    }

    @Test fun friendsFlightsViewModelKeepsOldListWhenOffline() = runTest {
        val api = FakeWave2Api().apply { answers["friends_flights"] = Json.parseToJsonElement(FRIENDS_FLIGHTS) }
        val vm = FriendsFlightsViewModel(SupabaseFriendsRepository(caller(api)), app.flytrack.testing.FakeClock(Instant.parse("2026-11-25T13:30:00Z")))
        vm.refresh()
        assertEquals(1, vm.uiState.first { it.flights.isNotEmpty() }.flights.size)
        api.answers["friends_flights"] = BackendException.Network(java.io.IOException())
        vm.refresh()
        assertEquals(1, vm.uiState.value.flights.size)
    }

    @Test fun shareSwitchRevertsOnFailure() = runTest {
        val api = FakeWave2Api().apply { shares[TestData.FLIGHT_ID] = true }
        val vm = ShareWithFriendsViewModel(TestData.FLIGHT_ID, SupabaseFriendsRepository(caller(api)))
        vm.load()
        assertEquals(true, vm.uiState.value.share)
        vm.setShare(false)
        assertEquals(false, vm.uiState.value.share)
        assertEquals(false, api.shares[TestData.FLIGHT_ID])

        api.shareError = BackendException.Network(java.io.IOException())
        vm.setShare(true)
        assertEquals(false, vm.uiState.value.share)
        assertTrue(vm.uiState.value.failed)
        vm.failureShown()
        assertFalse(vm.uiState.value.failed)
    }

    @Test fun shareSwitchHiddenForUntrackedFlights() = runTest {
        val vm = ShareWithFriendsViewModel(TestData.OTHER_FLIGHT_ID, SupabaseFriendsRepository(caller(FakeWave2Api())))
        vm.load()
        assertNull(vm.uiState.value.share)
        vm.setShare(false) // ignored
        assertNull(vm.uiState.value.share)
    }

    private companion object {
        /** Shape of `friends_flights()` from migration 20261126000017 (flight_public_json). */
        val FRIENDS_FLIGHTS = """
            [{"friend_id":"f1","display_name":"Asha","flight_id":"${TestData.FLIGHT_ID}",
              "flight":{"carrier":"6E","number":"2341","date":"2026-11-25","airline":"IndiGo","status":"en_route",
                "sched_dep":"2026-11-25T12:55:00+00:00","est_dep":"2026-11-25T13:00:00+00:00","act_dep":"2026-11-25T13:05:00+00:00",
                "sched_arr":"2026-11-25T15:10:00+00:00","est_arr":"2026-11-25T15:15:00+00:00","act_arr":null,
                "dep":{"iata":"DEL","name":"Indira Gandhi International Airport","city":"New Delhi","lat":28.5665,"lon":77.1031,"tz":"Asia/Kolkata","terminal":"1","gate":"42"},
                "arr":{"iata":"BOM","name":"Chhatrapati Shivaji International Airport","city":"Mumbai","lat":19.0887,"lon":72.8679,"tz":"Asia/Kolkata","terminal":"2","gate":null,"belt":"7"},
                "aircraft":{"model":"A321neo"},
                "live":{"lat":19.5,"lon":73.1,"alt_ft":12000,"speed_kts":300,"heading":200,"at":"2026-11-25T15:00:00+00:00"},
                "updated_at":"2026-11-25T15:00:00+00:00"}},
             {"friend_id":"f2","display_name":null,"flight":{"carrier":"AI","number":"101","date":"2026-11-25","dep":{"iata":"DEL"},"arr":{"iata":"JFK"}}}]
        """.trimIndent()
    }
}
