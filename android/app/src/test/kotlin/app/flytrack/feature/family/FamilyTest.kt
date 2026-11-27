package app.flytrack.feature.family

import app.flytrack.data.remote.BackendException
import app.flytrack.feature.friends.FakeWave2Api
import app.flytrack.feature.friends.caller
import app.flytrack.feature.social.FamilyGrant
import app.flytrack.feature.social.InMemorySocialStore
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Instant

class FamilyTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val memberView = Json.parseToJsonElement(
        """
        {"family_id":"fam1","role":"member","owner":{"user_id":"o1","display_name":"Asha"},"owner_pro":true,
         "pro_via_family":true,"members":[{"user_id":"${TestData.USER_ID}","display_name":null,"joined_at":"2026-11-20T10:00:00Z"}],
         "max_members":5,"can_invite":false,"invite":null}
        """.trimIndent(),
    )
    private val ownerView = Json.parseToJsonElement(
        """
        {"family_id":"fam1","role":"owner","owner":{"user_id":"o1","display_name":"  "},"owner_pro":true,"pro_via_family":false,
         "members":[],"max_members":5,"can_invite":true,"invite":{"code":"ABCD2345","expires_at":"2026-11-29T10:00:00Z"}}
        """.trimIndent(),
    )

    // ---- parsing ------------------------------------------------------------------------------------

    @Test
    fun `parses a member's view`() {
        val f = parseFamily(memberView)!!
        assertEquals(FamilyRole.MEMBER, f.role)
        assertFalse(f.isOwner)
        assertEquals("Asha", f.ownerName)
        assertTrue(f.proViaFamily)
        assertEquals(listOf(FamilyMember(TestData.USER_ID, null, Instant.parse("2026-11-20T10:00:00Z"))), f.members)
        assertEquals(4, f.seatsLeft)
        assertNull(f.invite)
    }

    @Test
    fun `parses an owner's view with the open invite`() {
        val f = parseFamily(ownerView)!!
        assertTrue(f.isOwner)
        assertNull("a blank name is no name", f.ownerName)
        assertTrue(f.canInvite)
        assertEquals(FamilyInvite("ABCD2345", Instant.parse("2026-11-29T10:00:00Z")), f.invite)
    }

    @Test
    fun `no family is null`() {
        assertNull(parseFamily(JsonNull))
        assertEquals("ABCD2345", parseInviteCode(JsonPrimitive("ABCD2345")))
        assertNull(parseInviteCode(JsonNull))
    }

    @Test
    fun `refusals map to typed errors`() {
        assertEquals(FamilyError.REQUIRES_YEARLY, familyError("42501", "family_requires_yearly"))
        assertEquals(FamilyError.ALREADY_IN_FAMILY, familyError("23505", "already_in_family"))
        assertEquals(FamilyError.ALREADY_IN_FAMILY, familyError("23505", null))
        assertEquals(FamilyError.INVALID_CODE, familyError("P0002", "invalid_code"))
        assertEquals(FamilyError.OWN_CODE, familyError("22023", null))
        assertEquals(FamilyError.FULL, familyError("P0001", "family_full"))
        assertEquals(FamilyError.INACTIVE, familyError("P0001", "family_inactive"))
        assertEquals(FamilyError.NOT_MEMBER, familyError("P0002", "not_member"))
        assertEquals(FamilyError.NOT_OWNER, familyError("42501", "not_owner"))
        assertNull("an unknown P0001 is not guessed", familyError("P0001", "boom"))
    }

    // ---- repository ---------------------------------------------------------------------------------

    private class Setup(scope: TestScope) {
        val api = FakeWave2Api()
        val store = InMemorySocialStore()
        val auth = FakeAuthRepository()
        val repo = SupabaseFamilyRepository(scope.caller(api, auth), auth, store)
    }

    @Test
    fun `my_family caches the family Pro for this user`() = runTest {
        val s = Setup(this)
        s.api.answers["my_family"] = memberView
        val result = s.repo.myFamily()
        assertTrue(result is FamilyResult.Ok && result.value?.familyId == "fam1")
        assertEquals(FamilyGrant(TestData.USER_ID, "Asha"), s.store.family.value)

        s.api.answers["my_family"] = ownerView
        s.repo.myFamily()
        assertNull("an owner's Pro is their own", s.store.family.value)
    }

    @Test
    fun `joining normalises the code and refreshes the family`() = runTest {
        val s = Setup(this)
        s.api.answers["accept_family_invite"] = buildJsonObject {
            put("family_id", "fam1")
            put("owner_id", "o1")
            put("owner_name", "Asha")
        }
        s.api.answers["my_family"] = memberView

        val result = s.repo.acceptInvite("abcd-2345")

        assertEquals(FamilyResult.Ok(JoinedFamily("fam1", "o1", "Asha")), result)
        assertEquals("ABCD2345", s.api.calls.first().second["p_code"]!!.jsonPrimitive.content)
        assertEquals(listOf("accept_family_invite", "my_family"), s.api.calls.map { it.first })
        assertEquals(FamilyGrant(TestData.USER_ID, "Asha"), s.store.family.value)
    }

    @Test
    fun `a malformed code is refused without a call`() = runTest {
        val s = Setup(this)
        assertEquals(FamilyResult.Refused(FamilyError.INVALID_CODE), s.repo.acceptInvite("hello"))
        assertTrue(s.api.calls.isEmpty())
    }

    @Test
    fun `leave clears the cached grant and errors stay typed`() = runTest {
        val s = Setup(this)
        s.store.family.value = FamilyGrant(TestData.USER_ID, "Asha")
        assertEquals(FamilyResult.Ok(Unit), s.repo.leave())
        assertNull(s.store.family.value)

        s.api.answers["create_family_invite"] = BackendException.Http(403, "42501", "family_requires_yearly")
        assertEquals(FamilyResult.Refused(FamilyError.REQUIRES_YEARLY), s.repo.createInvite())
        s.api.answers["remove_family_member"] = BackendException.Network(IOException("x"))
        assertEquals(FamilyResult.Offline, s.repo.removeMember("m1"))
        assertEquals("m1", s.api.calls.last().second["p_user"]!!.jsonPrimitive.content)
    }

    // ---- view model ---------------------------------------------------------------------------------

    private class FakeFamilyRepository : FamilyRepository {
        var family: FamilyInfo? = null
        var createResult: FamilyResult<String> = FamilyResult.Ok("ABCD2345")
        var acceptResult: FamilyResult<JoinedFamily> = FamilyResult.Ok(JoinedFamily("fam1", "o1", "Asha"))
        var myFamilyResult: FamilyResult<FamilyInfo?>? = null
        val removed = mutableListOf<String>()
        var left = 0

        override suspend fun myFamily(): FamilyResult<FamilyInfo?> = myFamilyResult ?: FamilyResult.Ok(family)
        override suspend fun createInvite(): FamilyResult<String> = createResult
        override suspend fun acceptInvite(code: String): FamilyResult<JoinedFamily> = acceptResult
        override suspend fun leave(): FamilyResult<Unit> {
            left++
            family = null
            return FamilyResult.Ok(Unit)
        }
        override suspend fun removeMember(userId: String): FamilyResult<Unit> {
            removed += userId
            return FamilyResult.Ok(Unit)
        }
    }

    @Test
    fun `the screen loads, joins with a pasted invite and shows the family`() = runTest {
        val repo = FakeFamilyRepository()
        val vm = FamilyViewModel("Join my family, code abcd-2345 thanks", repo)
        assertEquals(FamilyLoad.LOADED, vm.uiState.value.load)
        assertEquals("ABCD2345", vm.uiState.value.codeInput)

        repo.family = parseFamily(memberView)
        vm.join()

        assertEquals(FamilyMessage.Joined("Asha"), vm.uiState.value.message)
        assertEquals("fam1", vm.uiState.value.family?.familyId)
        assertEquals("", vm.uiState.value.codeInput)
        assertNull(vm.uiState.value.running)
    }

    @Test
    fun `join checks the code first and shows refusals`() = runTest {
        val repo = FakeFamilyRepository()
        val vm = FamilyViewModel(null, repo)
        vm.onCodeInputChange("abc")
        vm.join()
        assertTrue(vm.uiState.value.codeInvalid)

        vm.onCodeInputChange("ABCD2345")
        repo.acceptResult = FamilyResult.Refused(FamilyError.FULL)
        vm.join()
        assertEquals(FamilyMessage.Refused(FamilyError.FULL), vm.uiState.value.message)
    }

    @Test
    fun `the owner shares the open code without a new call, or creates one`() = runTest {
        val repo = FakeFamilyRepository()
        repo.family = parseFamily(ownerView)
        val vm = FamilyViewModel(null, repo)
        vm.shareInvite()
        val share = vm.uiState.value.inviteShare!!
        assertEquals("ABCD2345", share.code)
        vm.inviteHandled(share)
        assertNull(vm.uiState.value.inviteShare)

        repo.family = null
        vm.refresh()
        repo.createResult = FamilyResult.Ok("WXYZ6789")
        vm.shareInvite(copyOnly = true)
        assertEquals(FamilyInviteShare("WXYZ6789", copyOnly = true, id = 2), vm.uiState.value.inviteShare)
    }

    @Test
    fun `leaving and removing report back and reload`() = runTest {
        val repo = FakeFamilyRepository()
        repo.family = parseFamily(ownerView)
        val vm = FamilyViewModel(null, repo)
        vm.remove("m1")
        assertEquals(listOf("m1"), repo.removed)
        assertEquals(FamilyMessage.Removed, vm.uiState.value.message)
        vm.messageShown(FamilyMessage.Removed)
        vm.leave()
        assertEquals(FamilyMessage.Ended, vm.uiState.value.message)
        assertNull(vm.uiState.value.family)
    }

    @Test
    fun `a failed first load shows offline, a failed refresh keeps the family`() = runTest {
        val repo = FakeFamilyRepository()
        repo.myFamilyResult = FamilyResult.Offline
        val vm = FamilyViewModel(null, repo)
        assertEquals(FamilyLoad.OFFLINE, vm.uiState.value.load)

        repo.myFamilyResult = null
        repo.family = parseFamily(memberView)
        vm.refresh()
        assertEquals(FamilyLoad.LOADED, vm.uiState.value.load)
        repo.myFamilyResult = FamilyResult.Offline
        vm.refresh()
        assertEquals(FamilyLoad.LOADED, vm.uiState.value.load)
        assertEquals("fam1", vm.uiState.value.family?.familyId)
    }
}
