package app.flytrack.feature.settings

import app.flytrack.data.remote.AlertPrefsDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime
import java.util.Locale

/** `profiles.alert_language` (P2 contract, section 4). Hinglish exists only for alerts, not for the app UI. */
enum class AlertLanguage(val wire: String) {
    EN("en"),
    HI("hi"),
    HINGLISH("hinglish");

    companion object {
        /** Unknown or missing values read as English, like the server's renderer. */
        fun fromWire(value: String?): AlertLanguage = entries.firstOrNull { it.wire == value } ?: EN
    }
}

/** The contract's default: Hindi when the phone's language is Hindi, else English. */
fun defaultAlertLanguage(deviceLanguage: String?): AlertLanguage =
    if (deviceLanguage?.lowercase()?.substringBefore('-')?.substringBefore('_') == "hi") AlertLanguage.HI else AlertLanguage.EN

/** The user's alert settings, kept on the server in `profiles`. */
data class AlertPrefs(
    val quietHoursEnabled: Boolean = false,
    val quietStart: LocalTime = DEFAULT_QUIET_START,
    val quietEnd: LocalTime = DEFAULT_QUIET_END,
    /** IANA zone the quiet hours are read in. */
    val quietTz: String = DEFAULT_QUIET_TZ,
    val language: AlertLanguage = AlertLanguage.EN,
) {
    companion object {
        val DEFAULT_QUIET_START: LocalTime = LocalTime.of(23, 0)
        val DEFAULT_QUIET_END: LocalTime = LocalTime.of(7, 0)
        const val DEFAULT_QUIET_TZ = "Asia/Kolkata"
    }
}

/** One change the user made in Settings. */
sealed interface AlertPrefsChange {
    data class QuietHoursEnabled(val enabled: Boolean) : AlertPrefsChange
    data class QuietStart(val time: LocalTime) : AlertPrefsChange
    data class QuietEnd(val time: LocalTime) : AlertPrefsChange
    data class Language(val language: AlertLanguage) : AlertPrefsChange
}

/** Postgres `time` ("23:00:00", "07:30", "06:15:00.5") to a minute-precision time; null when unreadable. */
fun parsePgTime(value: String?): LocalTime? {
    val match = PG_TIME.matchEntire(value?.trim().orEmpty()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > 23 || minute > 59) return null
    return LocalTime.of(hour, minute)
}

/**
 * "HH:mm", which Postgres reads as a `time`. Always ASCII digits: a default-locale format would write
 * "२३:००" on a Marathi or Nepali phone (Devanagari is their default numbering) and the PATCH would fail.
 */
fun formatPgTime(time: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

/**
 * [deviceTz] when the server will accept it as `quiet_tz` (migration 15: a named IANA zone), else null.
 * Phones can report custom ids such as "GMT+05:30"; sending one would make the whole quiet-hours write
 * fail, so the zone is then left as it is on the server.
 */
fun acceptableQuietTz(deviceTz: String?): String? {
    val id = deviceTz?.trim().orEmpty()
    if (id.length !in 1..64 || !QUIET_TZ.matches(id)) return null
    if (id.startsWith("posix/") || id.startsWith("right/") || id == "localtime" || id == "posixrules") return null
    // Offsets without a region ("GMT+5", "Etc/GMT-5") pass the shape check but the user means a place.
    if (GMT_OFFSET.matches(id)) return null
    return id
}

private val QUIET_TZ = Regex("^[A-Za-z][A-Za-z0-9_+/-]*$")
private val GMT_OFFSET = Regex("^(Etc/)?GMT[+-]\\d+$")

private val PG_TIME = Regex("""^(\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$""")

fun AlertPrefsDto.toAlertPrefs(): AlertPrefs = AlertPrefs(
    quietHoursEnabled = quietHoursEnabled,
    quietStart = parsePgTime(quietStart) ?: AlertPrefs.DEFAULT_QUIET_START,
    quietEnd = parsePgTime(quietEnd) ?: AlertPrefs.DEFAULT_QUIET_END,
    quietTz = quietTz?.takeIf { it.isNotBlank() } ?: AlertPrefs.DEFAULT_QUIET_TZ,
    language = AlertLanguage.fromWire(alertLanguage),
)

/**
 * [change] applied locally (optimistic UI). A quiet-hours change also moves the zone to [deviceTz] when the
 * server accepts it ([acceptableQuietTz]); otherwise the zone stays as it was.
 */
fun AlertPrefs.apply(change: AlertPrefsChange, deviceTz: String): AlertPrefs {
    val tz = acceptableQuietTz(deviceTz) ?: quietTz
    return when (change) {
        is AlertPrefsChange.QuietHoursEnabled -> copy(quietHoursEnabled = change.enabled, quietTz = tz)
        is AlertPrefsChange.QuietStart -> copy(quietStart = change.time, quietTz = tz)
        is AlertPrefsChange.QuietEnd -> copy(quietEnd = change.time, quietTz = tz)
        is AlertPrefsChange.Language -> copy(language = change.language)
    }
}

/**
 * The `profiles` PATCH for [change]: only user-editable columns (column grants of migration 15). Quiet hours
 * are read in the zone of the phone that set them, so every quiet-hours change also writes `quiet_tz`.
 */
fun alertPrefsPatch(change: AlertPrefsChange, deviceTz: String): JsonObject = buildJsonObject {
    when (change) {
        is AlertPrefsChange.QuietHoursEnabled -> put("quiet_hours_enabled", change.enabled)
        is AlertPrefsChange.QuietStart -> put("quiet_start", formatPgTime(change.time))
        is AlertPrefsChange.QuietEnd -> put("quiet_end", formatPgTime(change.time))
        is AlertPrefsChange.Language -> put("alert_language", change.language.wire)
    }
    if (change !is AlertPrefsChange.Language) acceptableQuietTz(deviceTz)?.let { put("quiet_tz", it) }
}
