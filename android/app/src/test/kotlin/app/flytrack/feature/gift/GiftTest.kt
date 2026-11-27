package app.flytrack.feature.gift

import android.app.Activity
import app.flytrack.data.remote.BackendException
import app.flytrack.feature.billing.PurchaseHost
import app.flytrack.feature.billing.StorePrice
import app.flytrack.feature.friends.FakeWave2Api
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.caller
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Instant

class GiftCodeTest {
    @Test
    fun normalisesLikeTheServer() {
        assertEquals("ABCD-EFGH-JKMN", normalizeGiftCode("abcd efgh jkmn"))
        assertEquals("ABCD-EFGH-JKMN", normalizeGiftCode("ABCDEFGHJKMN"))
        assertEquals("ABCD-EFGH-JKMN", normalizeGiftCode(" abcd-efgh-jkmn "))
        assertEquals("2345-6789-ABCD", normalizeGiftCode("2345-6789-abcd"))
        // 0, O, 1, I and L are not in the alphabet.
        assertNull(normalizeGiftCode("ABCD-EFGH-JKM0"))
        assertNull(normalizeGiftCode("ABCD-EFGH-JKL1"))
        assertNull(normalizeGiftCode("ABCD-EFGH"))
        assertNull(normalizeGiftCode(""))
    }

    @Test
    fun findsTheCodeInAPastedMessage() {
        val message = "Maine aapko HeyGate Pro gift kiya hai! Code: ABCD-EFGH-JKMN\nI've gifted you HeyGate Pro! Code: ABCD-EFGH-JKMN."
        assertEquals("ABCD-EFGH-JKMN", extractGiftCode(message))
        assertEquals("WXYZ-2345-6789", extractGiftCode("code wxyz 2345 6789 thanks"))
        assertNull(extractGiftCode("no code in here"))
    }

    @Test
    fun formats() {
        assertEquals("ABCD-EFGH-JKMN", formatGiftCode("abcdefghjkmn"))
        assertEquals("SHORT", formatGiftCode("SHORT"))
    }

    @Test
    fun products() {
        assertEquals(GiftProduct.YEAR, GiftProduct.fromProductId("heygate_gift_year"))
        assertEquals(GiftProduct.TRIP, GiftProduct.fromProductId("heygate_gift_trip"))
        assertEquals(365, GiftProduct.YEAR.days)
        assertEquals(7, GiftProduct.TRIP.days)
        assertNull(GiftProduct.fromProductId("heygate_trip_pass"))
        assertNull(GiftProduct.fromProductId(null))
    }
}

class GiftParserTest {
    private val myGifts = Json.parseToJsonElement(
        """
        [
          {"code":"ABCD-EFGH-JKMN","product_id":"heygate_gift_year","days":365,"status":"unused","created_at":"2026-11-27T10:00:00.123456+00:00","redeemed_at":null},
          {"code":"WXYZ-2345-6789","product_id":"heygate_gift_trip","days":7,"status":"redeemed","created_at":"2026-11-20T10:00:00+00:00","redeemed_at":"2026-11-21T08:00:00+00:00"},
          {"code":"PQRS-TUVW-XYZ2","product_id":"heygate_gift_trip","days":7,"status":"voided","created_at":"2026-11-19T10:00:00+00:00","redeemed_at":null}
        ]
        """,
    )

    @Test
    fun parsesMyGifts() {
        val gifts = GiftParser.gifts(myGifts)
        assertEquals(listOf(GiftStatus.UNUSED, GiftStatus.REDEEMED, GiftStatus.VOIDED), gifts.map { it.status })
        assertEquals(GiftProduct.YEAR, gifts[0].product)
        assertEquals(Instant.parse("2026-11-27T10:00:00.123456Z"), gifts[0].createdAt)
        assertEquals(Instant.parse("2026-11-21T08:00:00Z"), gifts[1].redeemedAt)
        assertTrue(GiftParser.gifts(JsonNull).isEmpty())
        assertTrue(GiftParser.gifts(Json.parseToJsonElement("[]")).isEmpty())
    }

    @Test
    fun redeemAnswers() {
        val ok = GiftParser.redeem(Json.parseToJsonElement("""{"pro_until":"2027-11-27T10:00:00+00:00","days":365}"""))
        assertEquals(RedeemOutcome.Redeemed(Instant.parse("2027-11-27T10:00:00Z"), 365), ok)
        val errors = mapOf(
            "invalid_code" to RedeemOutcome.InvalidCode,
            "already_redeemed" to RedeemOutcome.AlreadyRedeemed,
            "voided" to RedeemOutcome.Voided,
            "own_gift" to RedeemOutcome.OwnGift,
            "rate_limited" to RedeemOutcome.RateLimited,
            "lifetime_pro" to RedeemOutcome.LifetimePro,
        )
        for ((code, expected) in errors) {
            assertEquals(expected, GiftParser.redeem(Json.parseToJsonElement("""{"error":"$code"}""")))
        }
        assertTrue(GiftParser.redeem(JsonNull) is RedeemOutcome.Failed)
        // Raised errors of an older server.
        assertEquals(RedeemOutcome.InvalidCode, GiftParser.redeemError(null, "P0002"))
        assertEquals(RedeemOutcome.AlreadyRedeemed, GiftParser.redeemError("x", "23505"))
    }

    @Test
    fun newCodeIsTheOneThatWasNotThereBefore() {
        val gifts = GiftParser.gifts(myGifts)
        assertEquals("ABCD-EFGH-JKMN", GiftParser.newCode(emptySet(), gifts, GiftProduct.YEAR)?.code)
        assertNull(GiftParser.newCode(setOf("ABCD-EFGH-JKMN"), gifts, GiftProduct.YEAR))
        // Redeemed or voided codes are never "the new one".
        assertNull(GiftParser.newCode(setOf("ABCD-EFGH-JKMN"), gifts, GiftProduct.TRIP))
    }
}

class SupabaseGiftRepositoryTest {
    @Test
    fun redeemSendsTheCanonicalCodeAndRefreshesTheProfile() = runTest {
        val api = FakeWave2Api().apply { answers["redeem_gift"] = Json.parseToJsonElement("""{"pro_until":"2026-12-04T10:00:00Z","days":7}""") }
        val profiles = FakeProfileRepository()
        val repo = SupabaseGiftRepository(caller(api), profiles)
        assertEquals(RedeemOutcome.Redeemed(Instant.parse("2026-12-04T10:00:00Z"), 7), repo.redeem("wxyz 2345 6789"))
        val (name, args) = api.calls.single()
        assertEquals("redeem_gift", name)
        assertEquals("WXYZ-2345-6789", args.getValue("p_code").jsonPrimitive.content)
        assertEquals(1, profiles.refreshCalls)
    }

    @Test
    fun badInputNeverReachesTheServerAndErrorsDoNotRefresh() = runTest {
        val api = FakeWave2Api().apply { answers["redeem_gift"] = Json.parseToJsonElement("""{"error":"own_gift"}""") }
        val profiles = FakeProfileRepository()
        val repo = SupabaseGiftRepository(caller(api), profiles)
        assertEquals(RedeemOutcome.InvalidCode, repo.redeem("hello"))
        assertTrue(api.calls.isEmpty())
        assertEquals(RedeemOutcome.OwnGift, repo.redeem("ABCD-EFGH-JKMN"))
        assertEquals(0, profiles.refreshCalls)
        api.answers["redeem_gift"] = BackendException.Network(java.io.IOException("x"))
        assertEquals(RedeemOutcome.Offline, repo.redeem("ABCD-EFGH-JKMN"))
    }

    @Test
    fun pollsUntilTheWebhookCodeArrives() = runTest {
        val api = FakeWave2Api().apply { answers["my_gifts"] = Json.parseToJsonElement("[]") }
        val repo = SupabaseGiftRepository(caller(api), FakeProfileRepository())
        repo.pollDelays = List(3) { Duration.ZERO }
        assertNull(repo.awaitNewCode(emptySet(), GiftProduct.TRIP))
        assertEquals(3, api.calls.size)

        api.answers["my_gifts"] = Json.parseToJsonElement(
            """[{"code":"WXYZ-2345-6789","product_id":"heygate_gift_trip","days":7,"status":"unused","created_at":"2026-11-27T10:00:00Z"}]""",
        )
        assertEquals("WXYZ-2345-6789", repo.awaitNewCode(emptySet(), GiftProduct.TRIP)?.code)
    }
}

private class FakeGiftRepository : GiftRepository {
    var gifts: List<Gift> = emptyList()
    var redeemOutcome: RedeemOutcome = RedeemOutcome.Redeemed(null, 7)
    var newCode: Gift? = null
    var listOffline = false
    var awaited = 0
    val redeemed = mutableListOf<String>()
    override suspend fun myGifts(): RemoteResult<List<Gift>> = if (listOffline) RemoteResult.Offline else RemoteResult.Ok(gifts)
    override suspend fun redeem(code: String): RedeemOutcome {
        redeemed += code
        return redeemOutcome
    }
    override suspend fun awaitNewCode(before: Set<String>, product: GiftProduct): Gift? {
        awaited++
        return newCode
    }
}

private class FakeGiftStore(override val isAvailable: Boolean = true) : GiftStore {
    var offer: GiftOfferResult = GiftOfferResult.Available(
        listOf(GiftOption(GiftProduct.YEAR, StorePrice("₹4,999", 4_999_000_000, "INR")), GiftOption(GiftProduct.TRIP, StorePrice("₹99", 99_000_000, "INR"))),
    )
    var outcome: GiftPurchaseOutcome = GiftPurchaseOutcome.Purchased("GPA.1")
    val bought = mutableListOf<GiftProduct>()
    override suspend fun loadOptions(): GiftOfferResult = offer
    override suspend fun purchase(host: PurchaseHost, product: GiftProduct): GiftPurchaseOutcome {
        bought += product
        return outcome
    }
}

private object NoHost : PurchaseHost {
    override val activity: Activity get() = error("not used by the fake store")
}

@OptIn(ExperimentalCoroutinesApi::class)
class GiftViewModelsTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val gift = Gift("ABCD-EFGH-JKMN", GiftProduct.YEAR, GiftContract.PRODUCT_YEAR, 365, GiftStatus.UNUSED, null, null)

    @Test
    fun shopBuysThenShowsTheCode() = runTest(main.dispatcher) {
        val store = FakeGiftStore()
        val repo = FakeGiftRepository().apply { newCode = gift }
        val vm = GiftShopViewModel(store, repo)
        runCurrent()
        assertEquals(GiftProduct.YEAR, vm.uiState.value.selected)
        vm.select(GiftProduct.TRIP)
        vm.purchase(NoHost)
        advanceUntilIdle()
        assertEquals(listOf(GiftProduct.TRIP), store.bought)
        assertEquals(GiftPurchaseState.CodeReady(gift), vm.uiState.value.purchase)
        vm.startOver()
        assertEquals(GiftPurchaseState.Idle, vm.uiState.value.purchase)
    }

    @Test
    fun unknownCodesBeforePurchaseNeverShowAnOldCodeAsNew() = runTest(main.dispatcher) {
        val store = FakeGiftStore()
        // The list could not be read before buying, so an older unused code would look new.
        val repo = FakeGiftRepository().apply { newCode = gift; listOffline = true }
        val vm = GiftShopViewModel(store, repo)
        runCurrent()
        vm.purchase(NoHost)
        advanceUntilIdle()
        assertEquals(listOf(GiftProduct.YEAR), store.bought)
        assertEquals(0, repo.awaited)
        assertEquals(GiftPurchaseState.CodeLater, vm.uiState.value.purchase)
    }

    @Test
    fun codeLateAndPendingAndCancelled() = runTest(main.dispatcher) {
        val store = FakeGiftStore()
        val repo = FakeGiftRepository()
        val vm = GiftShopViewModel(store, repo)
        runCurrent()
        vm.purchase(NoHost)
        advanceUntilIdle()
        assertEquals(GiftPurchaseState.CodeLater, vm.uiState.value.purchase)

        store.outcome = GiftPurchaseOutcome.Pending
        vm.startOver()
        vm.purchase(NoHost)
        advanceUntilIdle()
        assertEquals(GiftShopMessage.PENDING, vm.uiState.value.message)
        assertEquals(GiftPurchaseState.Idle, vm.uiState.value.purchase)

        store.outcome = GiftPurchaseOutcome.Cancelled
        vm.messageShown()
        vm.purchase(NoHost)
        advanceUntilIdle()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun comingSoonWithoutStore() = runTest(main.dispatcher) {
        val vm = GiftShopViewModel(FakeGiftStore(isAvailable = false), FakeGiftRepository())
        runCurrent()
        assertEquals(GiftShopContent.ComingSoon, vm.uiState.value.content)
        val empty = GiftShopViewModel(FakeGiftStore().apply { offer = GiftOfferResult.NotAvailable }, FakeGiftRepository())
        runCurrent()
        assertEquals(GiftShopContent.ComingSoon, empty.uiState.value.content)
    }

    @Test
    fun redeemFlow() = runTest(main.dispatcher) {
        val repo = FakeGiftRepository()
        val vm = RedeemGiftViewModel(repo)
        vm.onInput("abcd")
        assertFalse(vm.uiState.value.canRedeem)
        vm.redeem()
        assertTrue(repo.redeemed.isEmpty())
        // Pasting the whole message keeps just the code.
        vm.onInput("Maine aapko HeyGate Pro gift kiya hai! Code: ABCD-EFGH-JKMN")
        assertEquals("ABCD-EFGH-JKMN", vm.uiState.value.input)
        assertTrue(vm.uiState.value.canRedeem)
        repo.redeemOutcome = RedeemOutcome.AlreadyRedeemed
        vm.redeem()
        advanceUntilIdle()
        assertEquals(RedeemOutcome.AlreadyRedeemed, vm.uiState.value.outcome)
        assertEquals("ABCD-EFGH-JKMN", vm.uiState.value.input)
        vm.onInput("ABCD-EFGH-JKMP")
        assertNull(vm.uiState.value.outcome)
        repo.redeemOutcome = RedeemOutcome.Redeemed(null, 365)
        vm.redeem()
        advanceUntilIdle()
        assertEquals(RedeemOutcome.Redeemed(null, 365), vm.uiState.value.outcome)
        assertEquals(listOf("ABCD-EFGH-JKMN", "ABCD-EFGH-JKMP"), repo.redeemed)
    }

    @Test
    fun prefillOnlyOnce() = runTest(main.dispatcher) {
        val vm = RedeemGiftViewModel(FakeGiftRepository())
        vm.prefill("Code: wxyz-2345-6789")
        assertEquals("WXYZ-2345-6789", vm.uiState.value.input)
        vm.prefill("ABCD-EFGH-JKMN")
        assertEquals("WXYZ-2345-6789", vm.uiState.value.input)
    }

    @Test
    fun myGifts() = runTest(main.dispatcher) {
        val repo = FakeGiftRepository().apply { gifts = listOf(gift) }
        val vm = MyGiftsViewModel(repo)
        runCurrent()
        assertEquals(MyGiftsContent.Loaded(listOf(gift)), vm.uiState.value)
    }
}
