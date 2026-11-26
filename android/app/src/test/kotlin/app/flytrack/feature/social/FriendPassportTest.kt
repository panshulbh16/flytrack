package app.flytrack.feature.social

import app.flytrack.data.remote.BackendException
import app.flytrack.feature.friends.FakeWave2Api
import app.flytrack.feature.friends.caller
import app.flytrack.feature.passport.CountedAirline
import app.flytrack.feature.passport.LongestFlight
import app.flytrack.feature.passport.PassportRepository
import app.flytrack.feature.passport.PassportResult
import app.flytrack.feature.passport.PassportScope
import app.flytrack.feature.passport.PassportTab
import app.flytrack.feature.passport.parsePassport
import app.flytrack.testing.FakeClock
import app.flytrack.testing.Fixtures
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class FriendPassportTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val answer = Json.parseToJsonElement(
        """
        {"friend_id":"f1","display_name":" Asha ","scope":"last12","year":2026,"pro_limited":true,"flights":7,
         "distance_km":9120,"airports":5,"countries":2,"country_codes":["IN","AE"],
         "airlines":[{"carrier":"6E","name":"IndiGo","count":4},{"carrier":"AI","name":"Air India","count":2},
                     {"carrier":"EK","name":null,"count":1},{"carrier":"UK","count":1}],
         "longest":{"carrier":"EK","number":"511","dep":"BOM","arr":"DXB","distance_km":1930}}
        """.trimIndent(),
    )

    @Test
    fun `parses the reduced passport`() {
        val p = parseFriendPassport(answer, 2026)
        assertEquals("f1", p.friendId)
        assertEquals("Asha", p.displayName)
        assertEquals(PassportScope.LAST_12_MONTHS, p.scope)
        assertTrue(p.proLimited)
        assertEquals(PassportSide(7, 9120, 5, 2, listOf(CountedAirline("6E", "IndiGo", 4), CountedAirline("AI", "Air India", 2), CountedAirline("EK", null, 1)), LongestFlight(null, "EK", "511", "BOM", "DXB", 1930)), p.side)
        assertEquals(listOf("IN", "AE"), p.countryCodes)
    }

    @Test
    fun `an empty friend passport has no longest flight`() {
        val p = parseFriendPassport(Json.parseToJsonElement("""{"friend_id":"f1","longest":null,"airlines":[]}"""), 2026)
        assertNull(p.side.longest)
        assertEquals(0, p.side.flights)
        assertEquals(2026, p.year)
    }

    @Test
    fun `compare rows name the leader and scale the bars`() {
        val mine = PassportSide(10, 5000, 3, 1, emptyList(), LongestFlight(null, "6E", "1", "DEL", "BOM", 1150))
        val friend = PassportSide(7, 9120, 3, 2, emptyList(), null)
        val rows = comparePassports(mine, friend)
        assertEquals(listOf(CompareKind.FLIGHTS, CompareKind.DISTANCE_KM, CompareKind.AIRPORTS, CompareKind.COUNTRIES, CompareKind.LONGEST_KM), rows.map { it.kind })
        assertEquals(CompareLeader.ME, rows[0].leader)
        assertEquals(CompareLeader.FRIEND, rows[1].leader)
        assertNull("a tie has no leader", rows[2].leader)
        assertEquals(1f, rows[1].friendFraction)
        assertEquals(5000f / 9120f, rows[1].mineFraction, 0.0001f)
        assertEquals(1150, rows[4].mine)
        assertEquals(0, rows[4].friend)
        assertEquals(0f, CompareRow(CompareKind.FLIGHTS, 0, 0).mineFraction)
        assertEquals(listOf("IN"), sharedCountries(listOf("IN", "SG"), listOf("AE", "IN")))
    }

    @Test
    fun `my passport reduces to the same side`() {
        val mine = parsePassport(Json.parseToJsonElement(Fixtures.read("passport_pro_all.json")).jsonObject, 2026)
        val side = mine.toSide()
        assertEquals(mine.flights, side.flights)
        assertEquals(mine.airports.size, side.airports)
        assertTrue(side.airlines.size <= TOP_AIRLINES)
    }

    @Test
    fun `the repository sends the friend and the year and types not_shared`() = runTest {
        val api = FakeWave2Api()
        val repo = SupabaseFriendPassportRepository(caller(api))
        api.answers["friend_passport"] = answer
        val loaded = repo.friendPassport("f1", 2026, 2026)
        assertTrue(loaded is FriendPassportResult.Loaded)
        val args = api.calls.single().second
        assertEquals("f1", args["p_friend"]!!.jsonPrimitive.content)
        assertEquals(2026, args["p_year"]!!.jsonPrimitive.int)

        repo.friendPassport("f1", null, 2026)
        assertNull("overview sends no year", api.calls.last().second["p_year"])

        api.answers["friend_passport"] = BackendException.Http(403, "42501", "not_shared")
        assertEquals(FriendPassportResult.NotShared, repo.friendPassport("f1", null, 2026))
        api.answers["friend_passport"] = BackendException.Network(IOException("x"))
        assertEquals(FriendPassportResult.Offline, repo.friendPassport("f1", null, 2026))
        api.answers["friend_passport"] = JsonNull
        assertTrue(repo.friendPassport("f1", null, 2026) is FriendPassportResult.Failed)
    }

    @Test
    fun `the view model loads both sides for the same period`() = runTest {
        val mine = parsePassport(Json.parseToJsonElement(Fixtures.read("passport_pro_all.json")).jsonObject, 2026)
        val years = mutableListOf<Int?>()
        val passports = object : PassportRepository {
            override suspend fun passport(year: Int?, fallbackYear: Int): PassportResult {
                years += year
                return PassportResult.Loaded(mine)
            }
        }
        val friendYears = mutableListOf<Int?>()
        var friendResult: FriendPassportResult = FriendPassportResult.Loaded(parseFriendPassport(answer, 2026))
        val friends = object : FriendPassportRepository {
            override suspend fun friendPassport(friendId: String, year: Int?, fallbackYear: Int): FriendPassportResult {
                friendYears += year
                return friendResult
            }
        }
        val vm = FriendPassportViewModel("f1", null, passports, friends, FakeClock())

        val loaded = vm.uiState.value.content as CompareContent.Loaded
        assertEquals("Asha", vm.uiState.value.friendName)
        assertEquals(mine.flights, loaded.rows[0].mine)
        assertEquals(7, loaded.rows[0].friend)

        vm.selectTab(PassportTab.YEAR)
        assertEquals(listOf(null, 2026), years)
        assertEquals(listOf(null, 2026), friendYears)

        friendResult = FriendPassportResult.NotShared
        vm.retry()
        assertEquals(CompareContent.NotShared, vm.uiState.value.content)
        friendResult = FriendPassportResult.Offline
        vm.retry()
        assertEquals(CompareContent.Offline, vm.uiState.value.content)
    }
}
