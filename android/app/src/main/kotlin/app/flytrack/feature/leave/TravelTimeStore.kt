package app.flytrack.feature.leave

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/** How long the user takes to reach each airport, entered once per airport (device-local, DataStore). */
interface TravelTimeStore {
    /** Minutes to [iata]; [LeaveRules.DEFAULT_TRAVEL_MIN] until the user sets it. */
    fun travelMinutes(iata: String): Flow<Int>

    /** Remembers [minutes] for [iata], clamped to the allowed range. */
    suspend fun setTravelMinutes(iata: String, minutes: Int)
}

class DataStoreTravelTimeStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : TravelTimeStore {

    private val preferences: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override fun travelMinutes(iata: String): Flow<Int> {
        val key = keyFor(iata)
        return preferences.map { prefs -> prefs[key]?.let(::clampTravelMinutes) ?: LeaveRules.DEFAULT_TRAVEL_MIN }.distinctUntilChanged()
    }

    override suspend fun setTravelMinutes(iata: String, minutes: Int) {
        dataStore.edit { it[keyFor(iata)] = clampTravelMinutes(minutes) }
    }

    private fun keyFor(iata: String) = intPreferencesKey("$KEY_PREFIX${iata.trim().uppercase()}")

    private companion object {
        const val KEY_PREFIX = "leave_travel_minutes_"
    }
}
