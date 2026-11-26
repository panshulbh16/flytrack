package app.flytrack.feature.passport

import app.flytrack.data.remote.AlertPrefsDto
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.TravelApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** In-memory [TravelApi]: answers from the properties, records every call, throws [failure] when set. */
class FakeTravelApi : TravelApi {
    var passportAnswer: (year: Int?) -> JsonObject = { JsonObject(emptyMap()) }
    val healthAnswers = mutableMapOf<String, JsonObject?>()
    var prefsRow: AlertPrefsDto? = AlertPrefsDto()

    /** Thrown by every call while non-null. */
    var failure: BackendException? = null

    /** Thrown only by the update call. */
    var updateFailure: BackendException? = null

    val calls = mutableListOf<String>()
    val patches = mutableListOf<JsonObject>()

    override suspend fun myPassport(year: Int?): JsonObject {
        calls += "myPassport($year)"
        failure?.let { throw it }
        return passportAnswer(year)
    }

    override suspend fun airportHealth(iata: String): JsonObject? {
        calls += "airportHealth($iata)"
        failure?.let { throw it }
        return healthAnswers[iata]
    }

    override suspend fun alertPrefs(userId: String): AlertPrefsDto? {
        calls += "alertPrefs($userId)"
        failure?.let { throw it }
        return prefsRow
    }

    override suspend fun updateAlertPrefs(userId: String, patch: JsonObject): AlertPrefsDto? {
        calls += "updateAlertPrefs($userId)"
        failure?.let { throw it }
        updateFailure?.let { throw it }
        patches += patch
        val row = prefsRow ?: return null
        fun str(key: String) = (patch[key] as? JsonPrimitive)?.contentOrNull
        prefsRow = row.copy(
            quietHoursEnabled = (patch["quiet_hours_enabled"] as? JsonPrimitive)?.booleanOrNull ?: row.quietHoursEnabled,
            quietStart = str("quiet_start")?.let { "$it:00" } ?: row.quietStart,
            quietEnd = str("quiet_end")?.let { "$it:00" } ?: row.quietEnd,
            quietTz = str("quiet_tz") ?: row.quietTz,
            alertLanguage = str("alert_language") ?: row.alertLanguage,
        )
        return prefsRow
    }
}

