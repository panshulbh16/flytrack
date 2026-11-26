package app.flytrack.feature.social

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/**
 * The signed-in user is Pro through a family (P3 B13): the owner's name for "Pro via {name}'s family".
 * Tied to [userId] so a sign-in switch never shows another account's family Pro.
 */
data class FamilyGrant(val userId: String, val ownerName: String?)

/** `profiles.crew_mode` of [userId], cached so the app hides upsells and mutes reminders offline too (B12). */
data class CrewModeFlag(val userId: String, val enabled: Boolean)

/** Device-local cache of the P3 social flags. The server stays the truth; this only answers quickly and offline. */
interface SocialStore {
    val familyGrant: Flow<FamilyGrant?>
    suspend fun setFamilyGrant(grant: FamilyGrant?)

    val crewMode: Flow<CrewModeFlag?>
    suspend fun setCrewMode(flag: CrewModeFlag?)
}

class DataStoreSocialStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SocialStore {

    private val preferences: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val familyGrant: Flow<FamilyGrant?> = preferences.map { p ->
        p[FAMILY_USER]?.let { user -> FamilyGrant(user, p[FAMILY_OWNER_NAME]?.ifEmpty { null }) }
    }.distinctUntilChanged()

    override suspend fun setFamilyGrant(grant: FamilyGrant?) {
        dataStore.edit { p ->
            if (grant == null) {
                p.remove(FAMILY_USER)
                p.remove(FAMILY_OWNER_NAME)
            } else {
                p[FAMILY_USER] = grant.userId
                p[FAMILY_OWNER_NAME] = grant.ownerName.orEmpty()
            }
        }
    }

    override val crewMode: Flow<CrewModeFlag?> = preferences.map { p ->
        p[CREW_USER]?.let { user -> CrewModeFlag(user, p[CREW_ENABLED] == true) }
    }.distinctUntilChanged()

    override suspend fun setCrewMode(flag: CrewModeFlag?) {
        dataStore.edit { p ->
            if (flag == null) {
                p.remove(CREW_USER)
                p.remove(CREW_ENABLED)
            } else {
                p[CREW_USER] = flag.userId
                p[CREW_ENABLED] = flag.enabled
            }
        }
    }

    private companion object {
        val FAMILY_USER = stringPreferencesKey("p3_family_grant_user")
        val FAMILY_OWNER_NAME = stringPreferencesKey("p3_family_grant_owner_name")
        val CREW_USER = stringPreferencesKey("p3_crew_mode_user")
        val CREW_ENABLED = booleanPreferencesKey("p3_crew_mode_enabled")
    }
}

/** Nothing cached and nothing kept (defaults of constructors used by older tests and previews). */
object NoSocialStore : SocialStore {
    override val familyGrant: Flow<FamilyGrant?> = kotlinx.coroutines.flow.flowOf(null)
    override suspend fun setFamilyGrant(grant: FamilyGrant?) = Unit
    override val crewMode: Flow<CrewModeFlag?> = kotlinx.coroutines.flow.flowOf(null)
    override suspend fun setCrewMode(flag: CrewModeFlag?) = Unit
}
