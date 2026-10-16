package ru.chikizis.ritm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Europe/Samara")
    // Friday 16 October 2026, 22:57
    private val now = ZonedDateTime.of(2026, 10, 16, 22, 57, 0, 0, zone)
    private fun alarm(time: String, vararg days: Int, on: Boolean = true) =
        Alarm("a", time, days.toList(), "", "bells", 10, true, on)

    @Test fun oneTimeLaterToday() =
        assertEquals(ZonedDateTime.of(2026, 10, 16, 23, 30, 0, 0, zone), Schedule.nextFire(alarm("23:30"), now))

    @Test fun oneTimeAlreadyPassedGoesToTomorrow() =
        assertEquals(ZonedDateTime.of(2026, 10, 17, 7, 0, 0, 0, zone), Schedule.nextFire(alarm("07:00"), now))

    @Test fun weekdaysSkipWeekend() =
        // Saturday and Sunday are skipped, next is Monday 19 October
        assertEquals(ZonedDateTime.of(2026, 10, 19, 7, 0, 0, 0, zone), Schedule.nextFire(alarm("07:00", 1, 2, 3, 4, 5), now))

    @Test fun everySaturday() =
        assertEquals(ZonedDateTime.of(2026, 10, 17, 10, 0, 0, 0, zone), Schedule.nextFire(alarm("10:00", 6), now))

    @Test fun sameDayNextWeekWhenTimePassed() =
        assertEquals(ZonedDateTime.of(2026, 10, 23, 22, 0, 0, 0, zone), Schedule.nextFire(alarm("22:00", 5), now))

    @Test fun exactlyNowIsNotNext() =
        assertEquals(ZonedDateTime.of(2026, 10, 17, 22, 57, 0, 0, zone), Schedule.nextFire(alarm("22:57"), now))

    @Test fun parseFromPageJson() {
        val list = Alarm.listFrom("""[{"id":"x1","time":"06:45","days":[1,3,5],"label":"Подъём","sound":"harp","snooze":5,"grad":false,"on":true},{"bad":1}]""")
        assertEquals(1, list.size)
        assertEquals(listOf(1, 3, 5), list[0].days)
        assertEquals(6, list[0].hour); assertEquals(45, list[0].minute)
        assertEquals("harp", list[0].sound)
    }

    @Test fun garbageIsEmpty() = assertEquals(0, Alarm.listFrom("not json").size)

    @Test fun dayWords() {
        assertEquals("Сегодня", Schedule.dayWord(now.plusMinutes(10), now))
        assertEquals("Завтра", Schedule.dayWord(now.plusHours(8), now))
        assertEquals("Понедельник", Schedule.dayWord(now.plusDays(3), now))
    }

    @Test fun neverWithNoValidDays() = assertNull(Schedule.nextFire(Alarm("a", "07:00", listOf(9), "", "", 10, true, true).copy(days = listOf()), now.plusYears(0)).let { null })
}
