package app.flytrack.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.flytrack.core.model.AlertType
import app.flytrack.feature.flights.presentation.AlertGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which alert types a newly tracked flight starts muted. Empty means "notify me of everything",
 * which is also the server's default. Per-flight switches on the detail screen can still differ.
 */
interface DefaultAlertPreferences {
    val muted: Flow<Set<AlertType>>

    /** Mutes or un-mutes every alert type in [types]. Other types are left as they are. */
    suspend fun setMuted(types: Set<AlertType>, muted: Boolean)

    companion object {
        /** Nothing muted. Used when a caller has no store (tests, previews). */
        val AllOn: DefaultAlertPreferences = object : DefaultAlertPreferences {
            override val muted: Flow<Set<AlertType>> = flowOf(emptySet())
            override suspend fun setMuted(types: Set<AlertType>, muted: Boolean) = Unit
        }
    }
}

@Singleton
class DataStoreDefaultAlertPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : DefaultAlertPreferences {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    override val muted: Flow<Set<AlertType>> = preferences.map { prefs ->
        prefs[KEY].orEmpty().mapNotNull { AlertType.fromWire(it) }.toSet()
    }.distinctUntilChanged()

    override suspend fun setMuted(types: Set<AlertType>, muted: Boolean) {
        if (types.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY].orEmpty().toMutableSet()
            val wires = types.map { it.wire }
            if (muted) current += wires else current -= wires.toSet()
            prefs[KEY] = current
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("settings_default_muted_alerts")
    }
}

/** The settings switches: one per [AlertGroup], on unless every type in the group is muted. */
fun alertToggles(muted: Set<AlertType>, isPro: Boolean): List<app.flytrack.feature.flights.presentation.AlertToggleUiModel> =
    AlertGroup.entries.map { group ->
        val locked = group.proOnly && !isPro
        app.flytrack.feature.flights.presentation.AlertToggleUiModel(
            group = group,
            enabled = group.types.any { it !in muted },
            locked = locked,
        )
    }
