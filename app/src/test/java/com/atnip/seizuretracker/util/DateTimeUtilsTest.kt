package com.atnip.seizuretracker.util

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DateTimeUtilsTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinLocaleAndTimeZone() {
        // formatDateTime/formatDate use Locale.getDefault() internally, so pin it for
        // deterministic assertions regardless of the machine/CI runner's default locale.
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millisFor(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month, day, hour, minute, 0)
        return cal.timeInMillis
    }

    @Test
    fun `formatDate renders month day year`() {
        val millis = millisFor(2024, Calendar.MARCH, 5, 14, 30)
        assertEquals("Mar 5, 2024", DateTimeUtils.formatDate(millis))
    }

    @Test
    fun `formatDateTime renders date and 12-hour time`() {
        val millis = millisFor(2024, Calendar.MARCH, 5, 14, 30)
        assertEquals("Mar 5, 2024 at 2:30 PM", DateTimeUtils.formatDateTime(millis))
    }

    @Test
    fun `formatDuration returns em dash for zero or negative`() {
        assertEquals("—", DateTimeUtils.formatDuration(0))
        assertEquals("—", DateTimeUtils.formatDuration(-5))
    }

    @Test
    fun `formatDuration under a minute shows only seconds`() {
        assertEquals("45s", DateTimeUtils.formatDuration(45))
    }

    @Test
    fun `formatDuration on an exact minute shows zero seconds`() {
        assertEquals("1m 0s", DateTimeUtils.formatDuration(60))
    }

    @Test
    fun `formatDuration mixes minutes and seconds`() {
        assertEquals("2m 5s", DateTimeUtils.formatDuration(125))
    }

    @Test
    fun `formatDuration has no special-casing for hours`() {
        // Documents current behavior: minutes accumulate past 60 rather than rolling into
        // an "h" unit, since formatDuration only ever divides by 60 once.
        assertEquals("61m 1s", DateTimeUtils.formatDuration(3661))
    }
}
