package app.flytrack.core.time

import app.flytrack.testing.FakeClock
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TimeFormatTest {
    private val depUtc = Instant.parse("2026-11-25T13:20:00Z")

    @Test
    fun `clock time is shown in the airport's zone, 24 hour`() {
        assertEquals("18:50", FlightTimeFormat.time(depUtc, "Asia/Kolkata")) // same example as the server's fcm test
        assertEquals("17:20", FlightTimeFormat.time(depUtc, "Asia/Dubai"))
        assertEquals("13:20", FlightTimeFormat.time(depUtc, "Europe/London"))
        assertEquals("08:20", FlightTimeFormat.time(depUtc, "America/New_York"))
        assertEquals("00:05", FlightTimeFormat.time(Instant.parse("2026-11-25T18:35:00Z"), "Asia/Kolkata"))
    }

    @Test
    fun `unknown or missing zone falls back to India like the server`() {
        assertEquals("18:50", FlightTimeFormat.time(depUtc, null))
        assertEquals("18:50", FlightTimeFormat.time(depUtc, "Not/AZone"))
        assertEquals("Asia/Kolkata", zoneOf("").id)
    }

    @Test
    fun `daylight saving is handled by the zone rules`() {
        assertEquals("14:00", FlightTimeFormat.time(Instant.parse("2026-07-01T13:00:00Z"), "Europe/London"))
        assertEquals("13:00", FlightTimeFormat.time(Instant.parse("2026-12-01T13:00:00Z"), "Europe/London"))
    }

    @Test
    fun `clock time always uses ASCII digits`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("18:50", FlightTimeFormat.time(depUtc, "Asia/Kolkata"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `dates are formatted in the airport's zone`() {
        val lateEvening = Instant.parse("2026-11-25T19:30:00Z") // already the 26th in India
        assertEquals("26 Nov", FlightTimeFormat.dayMonth(lateEvening, "Asia/Kolkata", Locale.ENGLISH))
        assertEquals("25 Nov", FlightTimeFormat.dayMonth(lateEvening, "Europe/London", Locale.ENGLISH))
        assertEquals("Thu, 26 Nov", FlightTimeFormat.weekdayDayMonth(lateEvening, "Asia/Kolkata", Locale.ENGLISH))
        assertEquals(LocalDate.parse("2026-11-26"), FlightTimeFormat.localDate(lateEvening, "Asia/Kolkata"))
    }

    @Test
    fun `plain dates`() {
        val date = LocalDate.parse("2026-11-05")
        assertEquals("5 Nov", FlightTimeFormat.dayMonth(date, Locale.ENGLISH))
        assertEquals("Thu, 5 Nov", FlightTimeFormat.weekdayDayMonth(date, Locale.ENGLISH))
        assertEquals("Thu, 5 Nov 2026", FlightTimeFormat.fullDate(date, Locale.ENGLISH))
    }

    @Test
    fun `arrival day offset compares local calendar days`() {
        val dep = Instant.parse("2026-11-25T17:00:00Z") // 22:30 IST
        val arr = Instant.parse("2026-11-25T19:15:00Z") // 00:45 IST next day
        assertEquals(1, FlightTimeFormat.arrivalDayOffset(dep, "Asia/Kolkata", arr, "Asia/Kolkata"))
        assertEquals(0, FlightTimeFormat.arrivalDayOffset(depUtc, "Asia/Kolkata", depUtc + 2.hours, "Asia/Kolkata"))
        // DEL 02:00 IST -> lands 03:30 in Dubai the same local day
        assertEquals(0, FlightTimeFormat.arrivalDayOffset(Instant.parse("2026-11-24T20:30:00Z"), "Asia/Kolkata", Instant.parse("2026-11-24T23:30:00Z"), "Asia/Dubai"))
        // westbound across the date line: leaves Tokyo on the 26th, lands in Honolulu on the 25th
        assertEquals(-1, FlightTimeFormat.arrivalDayOffset(Instant.parse("2026-11-25T15:30:00Z"), "Asia/Tokyo", Instant.parse("2026-11-25T22:30:00Z"), "Pacific/Honolulu"))
    }

    @Test
    fun `relative day counts calendar days`() {
        val today = LocalDate.parse("2026-11-22")
        assertEquals(RelativeDay.Today, relativeDay(today, today))
        assertEquals(RelativeDay.Tomorrow, relativeDay(LocalDate.parse("2026-11-23"), today))
        assertEquals(RelativeDay.Yesterday, relativeDay(LocalDate.parse("2026-11-21"), today))
        assertEquals(RelativeDay.InDays(3), relativeDay(LocalDate.parse("2026-11-25"), today))
        assertEquals(RelativeDay.DaysAgo(2), relativeDay(LocalDate.parse("2026-11-20"), today))
        assertEquals(RelativeDay.InDays(40), relativeDay(LocalDate.parse("2027-01-01"), today))
    }

    @Test
    fun `relative day of an instant is judged in the airport's zone`() {
        val now = Instant.parse("2026-11-22T18:00:00Z") // 23:30 IST on the 22nd
        val soon = Instant.parse("2026-11-22T19:00:00Z") // 00:30 IST on the 23rd
        assertEquals(RelativeDay.Tomorrow, relativeDay(soon, now, "Asia/Kolkata"))
        assertEquals(RelativeDay.Today, relativeDay(soon, now, "Europe/London"))
        assertEquals(RelativeDay.InDays(3), relativeDay(Instant.parse("2026-11-25T12:55:00Z"), now, "Asia/Kolkata"))
    }

    @Test
    fun `relative time rounds down to whole units`() {
        val now = Instant.parse("2026-11-22T10:00:00Z")
        fun rel(offset: Duration) = relativeTime(now + offset, now)
        assertEquals(RelativeTime(0, RelativeUnit.NOW, isPast = false), rel(Duration.ZERO))
        assertEquals(RelativeTime(0, RelativeUnit.NOW, isPast = false), rel(-59.seconds))
        assertEquals(RelativeTime(4, RelativeUnit.MINUTES, isPast = true), rel(-(4.minutes + 50.seconds)))
        assertEquals(RelativeTime(35, RelativeUnit.MINUTES, isPast = false), rel(35.minutes))
        assertEquals(RelativeTime(59, RelativeUnit.MINUTES, isPast = true), rel(-59.minutes))
        assertEquals(RelativeTime(1, RelativeUnit.HOURS, isPast = true), rel(-60.minutes))
        assertEquals(RelativeTime(23, RelativeUnit.HOURS, isPast = false), rel(23.hours + 59.minutes))
        assertEquals(RelativeTime(1, RelativeUnit.DAYS, isPast = false), rel(24.hours))
        assertEquals(RelativeTime(3, RelativeUnit.DAYS, isPast = true), rel(-(3.days + 5.hours)))
    }

    @Test
    fun `duration parts`() {
        assertEquals("2h 05m", (2.hours + 5.minutes).toDurationParts().format())
        assertEquals("45m", 45.minutes.toDurationParts().format())
        assertEquals("1h 00m", 60.minutes.toDurationParts().format())
        assertEquals("13h 30m", (13.hours + 30.minutes).toDurationParts().format())
        assertEquals("0m", Duration.ZERO.toDurationParts().format())
        assertEquals("0m", (-5).minutes.toDurationParts().format())
        assertEquals(DurationParts(2, 15), (2.hours + 14.minutes + 40.seconds).toDurationParts()) // rounds to the minute
        assertEquals("2 h 05 min", DurationParts(2, 5).format(hoursMinutesPattern = "%1\$d h %2\$02d min"))
    }

    @Test
    fun `clock helper gives today's date at the airport`() {
        val clock = FakeClock(Instant.parse("2026-11-22T19:00:00Z"))
        assertEquals(LocalDate.parse("2026-11-23"), clock.today("Asia/Kolkata"))
        assertEquals(LocalDate.parse("2026-11-22"), clock.today("UTC"))
        clock.advance(6.hours)
        assertEquals(LocalDate.parse("2026-11-23"), clock.today("UTC"))
    }

    @Test
    fun `search window mirrors the server's date check`() {
        val now = Instant.parse("2026-11-22T10:00:00Z")
        assertTrue(SearchWindow.contains(LocalDate.parse("2026-11-22"), now))
        assertTrue(SearchWindow.contains(LocalDate.parse("2026-11-21"), now))
        assertFalse("00:00Z of the 20th is more than two days back", SearchWindow.contains(LocalDate.parse("2026-11-20"), now))
        assertTrue(SearchWindow.contains(LocalDate.parse("2027-11-22"), now))
        assertFalse(SearchWindow.contains(LocalDate.parse("2027-11-23"), now))
        assertFalse(SearchWindow.contains(LocalDate.parse("2030-01-01"), now)) // the server test's "Date" case

        val range = SearchWindow.dates(now)
        assertEquals(LocalDate.parse("2026-11-21"), range.start)
        assertEquals(LocalDate.parse("2027-11-22"), range.endInclusive)
        assertTrue(SearchWindow.contains(range.start, now) && SearchWindow.contains(range.endInclusive, now))
    }

    @Test
    fun `search window at exactly midnight includes the boundary day`() {
        val midnight = Instant.parse("2026-11-22T00:00:00Z")
        assertTrue(SearchWindow.contains(LocalDate.parse("2026-11-20"), midnight))
        assertEquals(LocalDate.parse("2026-11-20"), SearchWindow.dates(midnight).start)
    }
}
