package app.flytrack.feature.social

import app.flytrack.data.remote.BackendException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/** [SocialStore] in memory. */
class InMemorySocialStore : SocialStore {
    val family = MutableStateFlow<FamilyGrant?>(null)
    val crew = MutableStateFlow<CrewModeFlag?>(null)
    override val familyGrant: Flow<FamilyGrant?> = family
    override suspend fun setFamilyGrant(grant: FamilyGrant?) {
        family.value = grant
    }
    override val crewMode: Flow<CrewModeFlag?> = crew
    override suspend fun setCrewMode(flag: CrewModeFlag?) {
        crew.value = flag
    }
}

/** [ProfileFlagsApi] over one in-memory row. */
class FakeProfileFlagsApi : ProfileFlagsApi {
    var row: ProfileFlags? = ProfileFlags()
    var error: BackendException? = null
    val patches = mutableListOf<JsonObject>()

    override suspend fun flags(userId: String): ProfileFlags? {
        error?.let { throw it }
        return row
    }

    override suspend fun update(userId: String, patch: JsonObject): ProfileFlags? {
        error?.let { throw it }
        patches += patch
        val current = row ?: return null
        var next = current
        patch["crew_mode"]?.let { next = next.copy(crewMode = it.jsonPrimitive.boolean) }
        patch["share_passport_with_friends"]?.let { next = next.copy(sharePassportWithFriends = it.jsonPrimitive.boolean) }
        row = next
        return next
    }
}
