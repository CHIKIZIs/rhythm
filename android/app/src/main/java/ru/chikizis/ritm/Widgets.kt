package ru.chikizis.ritm

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Home-screen widgets. The page sends a snapshot of the next 7 days:
 * {days: {"2026-10-17": {events:[{s,e,t,c}], tasks:[{id,t,p,d}]}}}
 */
object Widgets {
    private const val ROWS = 6

    fun data(ctx: Context): JSONObject = try { JSONObject(Store.get(ctx, "widget") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun onSync(ctx: Context, json: String) {
        Store.put(ctx, "widget", json)
        updateAll(ctx)
    }

    private fun today(ctx: Context): JSONObject? = data(ctx).optJSONObject("days")?.optJSONObject(LocalDate.now().toString())

    fun markTaskDone(ctx: Context, taskId: String, date: String) {
        val d = data(ctx)
        val tasks = d.optJSONObject("days")?.optJSONObject(date)?.optJSONArray("tasks") ?: return
        for (i in 0 until tasks.length()) tasks.optJSONObject(i)?.let { if (it.optString("id") == taskId) it.put("d", true) }
        Store.put(ctx, "widget", d.toString())
        updateAll(ctx)
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        fun ids(c: Class<*>) = mgr.getAppWidgetIds(ComponentName(ctx, c))
        ids(AlarmWidget::class.java).forEach { mgr.updateAppWidget(it, alarmViews(ctx)) }
        ids(TasksWidget::class.java).forEach { mgr.updateAppWidget(it, tasksViews(ctx)) }
        ids(PlanWidget::class.java).forEach { mgr.updateAppWidget(it, planViews(ctx)) }
        scheduleMidnight(ctx)
    }

    /** Refresh shortly after midnight so the widgets switch to the new day. */
    private fun scheduleMidnight(ctx: Context) {
        val at = ZonedDateTime.now().toLocalDate().plusDays(1).atTime(0, 1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(ctx, 9001, Intent(ctx, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
    }

    private fun color(ctx: Context, id: Int) = ContextCompat.getColor(ctx, id)
    private fun parse(c: String?, fallback: Int) = try { Color.parseColor(c) } catch (e: Exception) { fallback }

    fun alarmViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_alarm)
        val next = AlarmScheduler.nextAny(ctx)
        if (next == null) {
            v.setTextViewText(R.id.w_time, "--:--")
            v.setTextViewText(R.id.w_sub, "Будильников нет")
        } else {
            val now = ZonedDateTime.now()
            val t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next.at), now.zone)
            v.setTextViewText(R.id.w_time, Schedule.hhmm(t))
            val label = next.alarm.label.takeIf { it.isNotBlank() && next.alarm.id != "test" }
            val word = if (next.snoozed) "Отложен" else Schedule.dayWord(t, now)
            v.setTextViewText(R.id.w_sub, listOfNotNull(word, label).joinToString(" · "))
        }
        v.setOnClickPendingIntent(R.id.w_root, Notifs.openApp(ctx, "focus", null, 8001))
        return v
    }

    private val TASK_ROWS = (1..ROWS).map { i ->
        Triple(rid("t_row$i"), rid("t_dot$i"), rid("t_txt$i"))
    }
    private val PLAN_ROWS = (1..ROWS).map { i ->
        arrayOf(rid("p_row$i"), rid("p_time$i"), rid("p_bar$i"), rid("p_txt$i"))
    }
    private fun rid(name: String): Int = R.id::class.java.getField(name).getInt(null)

    fun tasksViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_tasks)
        val day = today(ctx)
        val arr = day?.optJSONArray("tasks") ?: JSONArray()
        val tasks = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .sortedWith(compareBy({ it.optBoolean("d") }, { it.optInt("p", 2) }))
        val done = tasks.count { it.optBoolean("d") }
        v.setTextViewText(R.id.t_sub, when {
            day == null -> "Открой Ритм, чтобы обновить"
            tasks.isEmpty() -> Schedule.weekday(LocalDate.now()).replaceFirstChar { it.uppercase() }
            else -> "Выполнено $done из ${tasks.size}"
        })
        v.setProgressBar(R.id.t_progress, 100, if (tasks.isEmpty()) 0 else done * 100 / tasks.size, false)
        val ink = color(ctx, R.color.r_ink); val muted = color(ctx, R.color.r_muted)
        val prio = mapOf(1 to color(ctx, R.color.r_hot), 2 to color(ctx, R.color.r_warm), 3 to color(ctx, R.color.r_calm))
        TASK_ROWS.forEachIndexed { i, (row, dot, txt) ->
            val t = tasks.getOrNull(i)
            if (t == null) { v.setViewVisibility(row, View.GONE); return@forEachIndexed }
            val isDone = t.optBoolean("d")
            v.setViewVisibility(row, View.VISIBLE)
            v.setTextViewText(txt, (if (isDone) "✓  " else "") + t.optString("t"))
            v.setTextColor(txt, if (isDone) muted else ink)
            v.setImageViewResource(dot, if (isDone) R.drawable.ic_check else R.drawable.dot)
            v.setInt(dot, "setColorFilter", if (isDone) color(ctx, R.color.r_calm) else prio[t.optInt("p", 2)] ?: muted)
            v.setOnClickPendingIntent(row, Notifs.openApp(ctx, "tasks", null, 8100))
        }
        v.setViewVisibility(R.id.t_more, if (tasks.size > ROWS) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.t_more, "и ещё ${tasks.size - ROWS}")
        v.setViewVisibility(R.id.t_empty, if (tasks.isEmpty()) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.t_empty, if (day == null) "Открой Ритм, чтобы обновить" else "На сегодня задач нет")
        v.setOnClickPendingIntent(R.id.t_root, Notifs.openApp(ctx, "tasks", null, 8101))
        v.setOnClickPendingIntent(R.id.t_add, Notifs.openApp(ctx, "tasks", "task", 8102))
        return v
    }

    fun planViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_plan)
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        val day = today(ctx)
        val arr = day?.optJSONArray("events") ?: JSONArray()
        val events = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.sortedBy { it.optString("s") }
        v.setTextViewText(R.id.p_title, Schedule.weekday(date).replaceFirstChar { it.uppercase() } + ", " + Schedule.dayMonth(date))
        val left = events.count { endOf(it) > now.toLocalTime() }
        v.setTextViewText(R.id.p_sub, when {
            day == null -> "Открой Ритм, чтобы обновить"
            events.isEmpty() -> "Свободный день"
            left == 0 -> "Все дела на сегодня прошли"
            else -> "Осталось $left из ${events.size}"
        })
        val ink = color(ctx, R.color.r_ink); val muted = color(ctx, R.color.r_muted); val accent = color(ctx, R.color.r_accent)
        // show from the first event that is not over yet, so the widget stays useful during the day
        val firstLive = events.indexOfFirst { endOf(it) > now.toLocalTime() }.let { if (it < 0) maxOf(0, events.size - ROWS) else it }
        val start = minOf(firstLive, maxOf(0, events.size - ROWS))
        PLAN_ROWS.forEachIndexed { i, ids ->
            val e = events.getOrNull(start + i)
            if (e == null) { v.setViewVisibility(ids[0], View.GONE); return@forEachIndexed }
            val past = endOf(e) <= now.toLocalTime()
            val live = !past && LocalTime.parse(e.optString("s", "00:00")) <= now.toLocalTime()
            v.setViewVisibility(ids[0], View.VISIBLE)
            v.setTextViewText(ids[1], e.optString("s"))
            v.setTextColor(ids[1], if (live) accent else muted)
            v.setInt(ids[2], "setColorFilter", if (past) muted else parse(e.optString("c"), accent))
            v.setTextViewText(ids[3], (if (live) "Сейчас: " else "") + e.optString("t"))
            v.setTextColor(ids[3], if (past) muted else ink)
            v.setOnClickPendingIntent(ids[0], Notifs.openApp(ctx, "plan", null, 8200))
        }
        v.setViewVisibility(R.id.p_more, if (events.size - start > ROWS) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.p_more, "и ещё ${events.size - start - ROWS}")
        v.setViewVisibility(R.id.p_empty, if (events.isEmpty()) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.p_empty, if (day == null) "Открой Ритм, чтобы обновить" else "Свободный день. Нажми +, чтобы добавить дело")
        v.setOnClickPendingIntent(R.id.p_root, Notifs.openApp(ctx, "plan", null, 8201))
        v.setOnClickPendingIntent(R.id.p_add, Notifs.openApp(ctx, "plan", "event", 8202))
        return v
    }

    private fun endOf(e: JSONObject): LocalTime = try {
        val end = e.optString("e"); val s = e.optString("s", "00:00")
        if (end.isNotEmpty()) LocalTime.parse(end) else LocalTime.parse(s).plusMinutes(30)
    } catch (_: Exception) { LocalTime.MAX }
}

class AlarmWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Widgets.updateAll(ctx) }
}
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Widgets.updateAll(ctx) }
}
class PlanWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Widgets.updateAll(ctx) }
}
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Widgets.updateAll(ctx)
        Reminders.scheduleAll(ctx)
    }
}
