package app.flytrack.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Instants are stored as epoch milliseconds (sortable in SQL), the notify_prefs map as JSON text. */
class Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    fun prefsToJson(value: Map<String, Boolean>): String = Json.encodeToString(PREFS, value)

    @TypeConverter
    fun jsonToPrefs(value: String): Map<String, Boolean> =
        runCatching { Json.decodeFromString(PREFS, value) }.getOrDefault(emptyMap())

    private companion object {
        val PREFS = MapSerializer(String.serializer(), Boolean.serializer())
    }
}
