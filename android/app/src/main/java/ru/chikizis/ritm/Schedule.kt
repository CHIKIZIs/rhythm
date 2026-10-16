package ru.chikizis.ritm

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One alarm as the web app stores it. days: 0 = Sunday … 6 = Saturday; empty = one time. */
data class Alarm(
    val id: String,
    val time: String,
    val days: List<Int>,
    val label: String,
    val sound: String,
    val snooze: Int,
    val grad: Boolean,
    val on: Boolean,
) {
    val hour get() = time.substringBefore(':').toIntOrNull()?.coerceIn(0, 23) ?: 7
    val minute get() = time.substringAfter(':').toIntOrNull()?.coerceIn(0, 59) ?: 0
    val oneTime get() = days.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("time", time).put("days", JSONArray(days)).put("label", label)
        .put("sound", sound).put("snooze", snooze).put("grad", grad).put("on", on)

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val d = o.optJSONArray("days") ?: JSONArray()
            return Alarm(
                id = o.optString("id"),
                time = o.optString("time", "07:00"),
                days = (0 until d.length()).map { d.optInt(it) }.filter { it in 0..6 },
                label = o.optString("label", ""),
                sound = o.optString("sound", "bells"),
                snooze = o.optInt("snooze", 10).let { if (it in 1..60) it else 10 },
                grad = o.optBoolean("grad", true),
                on = o.optBoolean("on", true),
            )
        }

        fun listFrom(json: String?): List<Alarm> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(json)
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }.filter { it.id.isNotEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

object Schedule {
    /** Web weekday numbering (0 = Sunday) for a date. */
    fun webDay(date: LocalDate): Int = date.dayOfWeek.value % 7

    /** Next moment this alarm should ring strictly after [now], or null if it never will. */
    fun nextFire(a: Alarm, now: ZonedDateTime): ZonedDateTime? {
        val zone = now.zone
        for (i in 0..7) {
            val date = now.toLocalDate().plusDays(i.toLong())
            if (a.days.isNotEmpty() && webDay(date) !in a.days) continue
            val t = ZonedDateTime.of(date, LocalTime.of(a.hour, a.minute), zone)
            if (t.isAfter(now)) return t
        }
        return null
    }

    fun nextFireMillis(a: Alarm, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        nextFire(a, ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone))?.toInstant()?.toEpochMilli()

    /** Key that marks a one-time alarm as already used, so a stale "on" from the page does not re-arm it. */
    fun onceKey(a: Alarm) = a.id + "@" + a.time

    private val WD = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")
    private val WD_SHORT = arrayOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")
    private val MONTHS = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    fun weekday(date: LocalDate) = WD[webDay(date)]
    fun weekdayShort(date: LocalDate) = WD_SHORT[webDay(date)]
    fun dayMonth(date: LocalDate) = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

    /** "Сегодня", "Завтра" or the weekday, for a moment relative to now. */
    fun dayWord(target: ZonedDateTime, now: ZonedDateTime): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())
        return when (diff) {
            0L -> "Сегодня"
            1L -> "Завтра"
            else -> weekday(target.toLocalDate()).replaceFirstChar { it.uppercase() }
        }
    }

    fun hhmm(t: ZonedDateTime) = "%02d:%02d".format(t.hour, t.minute)
}
