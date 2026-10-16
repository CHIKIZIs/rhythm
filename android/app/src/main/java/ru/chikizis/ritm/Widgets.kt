package ru.chikizis.ritm

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Home-screen widgets. The page sends a snapshot of the next 7 days:
 * {days: {"2026-10-17": {events:[{s,e,t,c}], tasks:[{id,t,p,d}]}}}
 * Colours follow the icon colour the user picked ("color" = filled, "plain" = theme background with coloured accents).
 */
object Widgets {
    private const val ROWS = 6

    /** Colours for one render. */
    class Look(ctx: Context) {
        val main = IconColors.main(ctx)
        val colored = IconColors.style(ctx) == "color"
        val bg = if (colored) main else ContextCompat.getColor(ctx, R.color.r_surface)
        val ink = if (colored) Color.WHITE else ContextCompat.getColor(ctx, R.color.r_ink)
        val muted = if (colored) ColorUtils.setAlphaComponent(Color.WHITE, 0xC0) else ContextCompat.getColor(ctx, R.color.r_muted)
        val accent = if (colored) Color.WHITE else main
        val track = if (colored) ColorUtils.setAlphaComponent(Color.WHITE, 0x40) else ContextCompat.getColor(ctx, R.color.r_line)
        val chip = if (colored) R.drawable.chip_glass else R.drawable.chip_bg
        val hot = if (colored) Color.parseColor("#FFB3C1") else ContextCompat.getColor(ctx, R.color.r_hot)
        val warm = if (colored) Color.parseColor("#FFE08A") else ContextCompat.getColor(ctx, R.color.r_warm)
        val calm = if (colored) Color.parseColor("#B8FFE6") else ContextCompat.getColor(ctx, R.color.r_calm)

        fun frame(v: RemoteViews, bgId: Int, sheenId: Int) {
            v.setInt(bgId, "setColorFilter", bg)
            v.setViewVisibility(sheenId, if (colored) View.VISIBLE else View.GONE)
        }
        fun progress(v: RemoteViews, id: Int) {
            if (Build.VERSION.SDK_INT >= 31) {
                v.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(if (colored) Color.WHITE else calm))
                v.setColorStateList(id, "setProgressBackgroundTintList", ColorStateList.valueOf(track))
            }
        }
    }

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
        try {
            ids(AlarmWidget::class.java).forEach { mgr.updateAppWidget(it, alarmViews(ctx)) }
            ids(TasksWidget::class.java).forEach { mgr.updateAppWidget(it, tasksViews(ctx)) }
            ids(PlanWidget::class.java).forEach { mgr.updateAppWidget(it, planViews(ctx)) }
            ids(TodayWidget::class.java).forEach { mgr.updateAppWidget(it, todayViews(ctx)) }
        } catch (_: Exception) {}
        scheduleMidnight(ctx)
    }

    /** Refresh shortly after midnight so the widgets switch to the new day. */
    private fun scheduleMidnight(ctx: Context) {
        val at = ZonedDateTime.now().toLocalDate().plusDays(1).atTime(0, 1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(ctx, 9001, Intent(ctx, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
    }

    private fun parse(c: String?, fallback: Int) = try { Color.parseColor(c) } catch (e: Exception) { fallback }
    private fun rid(name: String): Int = R.id::class.java.getField(name).getInt(null)
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    // ---------- next alarm ----------
    private fun nextAlarmText(ctx: Context): Pair<String, String>? {
        val next = AlarmScheduler.nextAny(ctx) ?: return null
        val now = ZonedDateTime.now()
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(next.at), now.zone)
        val label = next.alarm.label.takeIf { it.isNotBlank() && next.alarm.id != "test" }
        val word = if (next.snoozed) "Отложен" else Schedule.dayWord(t, now)
        return Schedule.hhmm(t) to listOfNotNull(word, label).joinToString(" · ")
    }

    fun alarmViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_alarm)
        val L = Look(ctx); L.frame(v, R.id.w_bg, R.id.w_sheen)
        val n = nextAlarmText(ctx)
        v.setTextViewText(R.id.w_time, n?.first ?: "--:--")
        v.setTextViewText(R.id.w_sub, n?.second ?: "Будильников нет")
        v.setTextColor(R.id.w_time, if (L.colored) Color.WHITE else L.main)
        v.setTextColor(R.id.w_head, L.muted); v.setTextColor(R.id.w_sub, L.muted)
        v.setInt(R.id.w_icon, "setColorFilter", L.accent)
        v.setOnClickPendingIntent(R.id.w_root, Notifs.openApp(ctx, "focus", null, 8001))
        return v
    }

    // ---------- tasks ----------
    private fun todayTasks(ctx: Context): List<JSONObject>? {
        val day = today(ctx) ?: return null
        val arr = day.optJSONArray("tasks") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .sortedWith(compareBy({ it.optBoolean("d") }, { it.optInt("p", 2) }))
    }

    private fun taskRow(v: RemoteViews, L: Look, row: Int, dot: Int, txt: Int, t: JSONObject?, open: PendingIntent) {
        if (t == null) { v.setViewVisibility(row, View.GONE); return }
        val isDone = t.optBoolean("d")
        v.setViewVisibility(row, View.VISIBLE)
        v.setTextViewText(txt, t.optString("t"))
        v.setTextColor(txt, if (isDone) L.muted else L.ink)
        v.setImageViewResource(dot, if (isDone) R.drawable.ic_check else R.drawable.dot)
        v.setInt(dot, "setColorFilter", if (isDone) L.calm else when (t.optInt("p", 2)) { 1 -> L.hot; 3 -> L.calm; else -> L.warm })
        v.setOnClickPendingIntent(row, open)
    }

    fun tasksViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_tasks)
        val L = Look(ctx); L.frame(v, R.id.t_bg, R.id.t_sheen); L.progress(v, R.id.t_progress)
        val tasks = todayTasks(ctx)
        val list = tasks ?: emptyList()
        val done = list.count { it.optBoolean("d") }
        v.setTextColor(R.id.t_title, L.ink); v.setTextColor(R.id.t_sub, L.muted); v.setTextColor(R.id.t_empty, L.muted); v.setTextColor(R.id.t_more, L.accent)
        v.setTextViewText(R.id.t_sub, when {
            tasks == null -> "Открой Ритм, чтобы обновить"
            list.isEmpty() -> cap(Schedule.weekday(LocalDate.now()))
            else -> "Выполнено $done из ${list.size}"
        })
        v.setProgressBar(R.id.t_progress, 100, if (list.isEmpty()) 0 else done * 100 / list.size, false)
        val open = Notifs.openApp(ctx, "tasks", null, 8100)
        for (i in 1..ROWS) taskRow(v, L, rid("t_row$i"), rid("t_dot$i"), rid("t_txt$i"), list.getOrNull(i - 1), open)
        v.setViewVisibility(R.id.t_more, if (list.size > ROWS) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.t_more, "и ещё ${list.size - ROWS}")
        v.setViewVisibility(R.id.t_empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.t_empty, if (tasks == null) "Открой Ритм, чтобы обновить" else "На сегодня задач нет")
        v.setInt(R.id.t_add, "setBackgroundResource", L.chip); v.setInt(R.id.t_add, "setColorFilter", L.accent)
        v.setOnClickPendingIntent(R.id.t_root, Notifs.openApp(ctx, "tasks", null, 8101))
        v.setOnClickPendingIntent(R.id.t_add, Notifs.openApp(ctx, "tasks", "task", 8102))
        return v
    }

    // ---------- plan ----------
    private fun todayEvents(ctx: Context): List<JSONObject>? {
        val day = today(ctx) ?: return null
        val arr = day.optJSONArray("events") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.sortedBy { it.optString("s") }
    }

    private fun endOf(e: JSONObject): LocalTime = try {
        val end = e.optString("e"); val s = e.optString("s", "00:00")
        if (end.isNotEmpty()) LocalTime.parse(end) else LocalTime.parse(s).plusMinutes(30)
    } catch (_: Exception) { LocalTime.MAX }

    /** Fills event rows starting from the first one that is not over yet. Returns how many did not fit. */
    private fun planRows(v: RemoteViews, L: Look, events: List<JSONObject>, ids: List<IntArray>, open: PendingIntent): Int {
        val now = LocalTime.now()
        val firstLive = events.indexOfFirst { endOf(it) > now }.let { if (it < 0) maxOf(0, events.size - ids.size) else it }
        val start = minOf(firstLive, maxOf(0, events.size - ids.size))
        ids.forEachIndexed { i, id ->
            val e = events.getOrNull(start + i)
            if (e == null) { v.setViewVisibility(id[0], View.GONE); return@forEachIndexed }
            val past = endOf(e) <= now
            val live = !past && (try { LocalTime.parse(e.optString("s", "00:00")) } catch (_: Exception) { LocalTime.MIN }) <= now
            v.setViewVisibility(id[0], View.VISIBLE)
            v.setTextViewText(id[1], e.optString("s"))
            v.setTextColor(id[1], if (live) L.accent else L.muted)
            v.setInt(id[2], "setColorFilter", if (past) L.muted else if (L.colored) Color.WHITE else parse(e.optString("c"), L.main))
            v.setTextViewText(id[3], (if (live) "Сейчас: " else "") + e.optString("t"))
            v.setTextColor(id[3], if (past) L.muted else L.ink)
            v.setOnClickPendingIntent(id[0], open)
        }
        return maxOf(0, events.size - start - ids.size)
    }

    fun planViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_plan)
        val L = Look(ctx); L.frame(v, R.id.p_bg, R.id.p_sheen)
        val date = LocalDate.now()
        val evs = todayEvents(ctx)
        val events = evs ?: emptyList()
        val now = LocalTime.now()
        v.setTextColor(R.id.p_title, L.ink); v.setTextColor(R.id.p_sub, L.muted); v.setTextColor(R.id.p_empty, L.muted); v.setTextColor(R.id.p_more, L.accent)
        v.setTextViewText(R.id.p_title, cap(Schedule.weekday(date)) + ", " + Schedule.dayMonth(date))
        val left = events.count { endOf(it) > now }
        v.setTextViewText(R.id.p_sub, when {
            evs == null -> "Открой Ритм, чтобы обновить"
            events.isEmpty() -> "Свободный день"
            left == 0 -> "Все дела на сегодня прошли"
            else -> "Осталось $left из ${events.size}"
        })
        val ids = (1..ROWS).map { intArrayOf(rid("p_row$it"), rid("p_time$it"), rid("p_bar$it"), rid("p_txt$it")) }
        val more = planRows(v, L, events, ids, Notifs.openApp(ctx, "plan", null, 8200))
        v.setViewVisibility(R.id.p_more, if (more > 0) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.p_more, "и ещё $more")
        v.setViewVisibility(R.id.p_empty, if (events.isEmpty()) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.p_empty, if (evs == null) "Открой Ритм, чтобы обновить" else "Свободный день. Нажми +, чтобы добавить дело")
        v.setInt(R.id.p_add, "setBackgroundResource", L.chip); v.setInt(R.id.p_add, "setColorFilter", L.accent)
        v.setOnClickPendingIntent(R.id.p_root, Notifs.openApp(ctx, "plan", null, 8201))
        v.setOnClickPendingIntent(R.id.p_add, Notifs.openApp(ctx, "plan", "event", 8202))
        return v
    }

    // ---------- today (4×2) ----------
    fun todayViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_today)
        val L = Look(ctx); L.frame(v, R.id.d_bg, R.id.d_sheen)
        val date = LocalDate.now()
        v.setTextViewText(R.id.d_date, cap(Schedule.weekday(date)) + ", " + Schedule.dayMonth(date))
        v.setTextColor(R.id.d_date, L.ink)
        // next alarm chip
        val n = nextAlarmText(ctx)
        v.setTextViewText(R.id.d_alarm_txt, if (n == null) "Нет" else n.first + " · " + n.second)
        v.setTextColor(R.id.d_alarm_txt, L.ink); v.setInt(R.id.d_alarm_icon, "setColorFilter", L.accent)
        v.setInt(R.id.d_alarm, "setBackgroundResource", L.chip)
        v.setOnClickPendingIntent(R.id.d_alarm, Notifs.openApp(ctx, "focus", null, 8300))
        listOf(R.id.d_plan_h, R.id.d_task_h, R.id.d_task_n, R.id.d_plan_empty, R.id.d_task_empty).forEach { v.setTextColor(it, L.muted) }
        // plan: the next two events
        val evs = todayEvents(ctx)
        val events = evs ?: emptyList()
        planRows(v, L, events, (1..2).map { intArrayOf(rid("d_row$it"), rid("d_time$it"), rid("d_bar$it"), rid("d_txt$it")) },
            Notifs.openApp(ctx, "plan", null, 8301))
        v.setViewVisibility(R.id.d_plan_empty, if (events.isEmpty()) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.d_plan_empty, if (evs == null) "Открой Ритм" else "Свободный день")
        // tasks: unfinished first
        val tasks = todayTasks(ctx) ?: emptyList()
        val done = tasks.count { it.optBoolean("d") }
        v.setTextViewText(R.id.d_task_n, if (tasks.isEmpty()) "" else "$done/${tasks.size}")
        val open = Notifs.openApp(ctx, "tasks", null, 8302)
        for (i in 1..2) taskRow(v, L, rid("dt_row$i"), rid("dt_dot$i"), rid("dt_txt$i"), tasks.getOrNull(i - 1), open)
        v.setViewVisibility(R.id.d_task_empty, if (tasks.isEmpty()) View.VISIBLE else View.GONE)
        v.setInt(R.id.d_add, "setBackgroundResource", L.chip); v.setInt(R.id.d_add, "setColorFilter", L.accent)
        v.setOnClickPendingIntent(R.id.d_add, Notifs.openApp(ctx, "tasks", "task", 8303))
        v.setOnClickPendingIntent(R.id.d_root, Notifs.openApp(ctx, "plan", null, 8304))
        return v
    }
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
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Widgets.updateAll(ctx) }
}
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Widgets.updateAll(ctx)
        Reminders.scheduleAll(ctx)
    }
}
