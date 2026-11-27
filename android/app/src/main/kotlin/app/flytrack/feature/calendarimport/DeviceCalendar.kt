package app.flytrack.feature.calendarimport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.flytrack.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Instant

/** What reading the device calendar gave. */
sealed interface DeviceCalendarResult {
    data class Events(val events: List<CalendarEvent>, val truncated: Boolean) : DeviceCalendarResult

    /** READ_CALENDAR is not granted (it is only asked for when the user taps "Scan my calendar"). */
    data object NoPermission : DeviceCalendarResult

    /** The calendar provider failed or is missing. */
    data object Unavailable : DeviceCalendarResult
}

/** Read-only access to the phone's calendars (P3 A3). */
interface DeviceCalendar {
    fun hasPermission(): Boolean

    /** Every event instance (recurring ones expanded) that starts between [from] and [to]. */
    suspend fun events(from: Instant, to: Instant): DeviceCalendarResult
}

class AndroidDeviceCalendar @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeviceCalendar {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun events(from: Instant, to: Instant): DeviceCalendarResult = withContext(io) {
        if (!hasPermission()) return@withContext DeviceCalendarResult.NoPermission
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from.toEpochMilliseconds())
            android.content.ContentUris.appendId(it, to.toEpochMilliseconds())
        }.build()
        try {
            val events = ArrayList<CalendarEvent>()
            var truncated = false
            context.contentResolver.query(uri, PROJECTION, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (events.size >= MAX_EVENTS) {
                        truncated = true
                        break
                    }
                    val row = DeviceEventRow(
                        eventId = cursor.getLong(0),
                        title = cursor.getString(1),
                        description = cursor.getString(2),
                        location = cursor.getString(3),
                        beginMillis = cursor.getLong(4),
                        endMillis = if (cursor.isNull(5)) null else cursor.getLong(5),
                        allDay = cursor.getInt(6) != 0,
                        status = if (cursor.isNull(7)) null else cursor.getInt(7),
                    )
                    events += row.toCalendarEvent()
                }
            } ?: return@withContext DeviceCalendarResult.Unavailable
            DeviceCalendarResult.Events(events, truncated)
        } catch (e: SecurityException) {
            DeviceCalendarResult.NoPermission
        } catch (e: IllegalArgumentException) {
            DeviceCalendarResult.Unavailable
        }
    }

    private companion object {
        /** A year of a busy calendar fits; anything beyond is noise for a flight scan. */
        const val MAX_EVENTS = 5000

        val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
        )
    }
}

/** One row of `CalendarContract.Instances`, free of android.* types so the mapping is JVM-tested. */
data class DeviceEventRow(
    val eventId: Long,
    val title: String?,
    val description: String?,
    val location: String?,
    val beginMillis: Long,
    val endMillis: Long?,
    val allDay: Boolean,
    /** `Events.STATUS`: 0 tentative, 1 confirmed, 2 cancelled. */
    val status: Int?,
) {
    /**
     * The parser's (Google-shaped) event: timed instances become UTC instants; all-day ones keep their date
     * (the provider stores all-day events at midnight UTC). The id includes the start, so each occurrence of
     * a recurring event is its own suggestion.
     */
    fun toCalendarEvent(): CalendarEvent = CalendarEvent(
        id = "$eventId:$beginMillis",
        summary = title,
        description = description,
        location = location,
        start = time(beginMillis),
        end = endMillis?.let(::time),
        status = if (status == STATUS_CANCELED) "cancelled" else null,
    )

    private fun time(millis: Long): EventTime =
        if (allDay) {
            EventTime(date = Instant.fromEpochMilliseconds(millis).toString().take(10))
        } else {
            EventTime(dateTime = Instant.fromEpochMilliseconds(millis).toString())
        }

    private companion object {
        const val STATUS_CANCELED = 2
    }
}
