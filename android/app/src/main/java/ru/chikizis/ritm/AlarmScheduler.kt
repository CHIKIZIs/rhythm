package ru.chikizis.ritm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.ZonedDateTime

/** Arms every enabled alarm (and pending snoozes) with AlarmManager.setAlarmClock, which fires even in deep sleep. */
object AlarmScheduler {
    private const val KEY_ARMED = "armed_alarm_codes"
    private const val KEY_ONCE = "once_done"

    data class Next(val at: Long, val alarm: Alarm, val snoozed: Boolean)

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun fireIntent(ctx: Context, code: Int, alarm: Alarm, snoozed: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "ru.chikizis.ritm.ALARM.$code"
            putExtra("alarm", alarm.toJson().toString())
            putExtra("snoozed", snoozed)
        }
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelCode(ctx: Context, code: Int) {
        val i = Intent(ctx, AlarmReceiver::class.java).apply { action = "ru.chikizis.ritm.ALARM.$code" }
        val pi = PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        if (pi != null) {
            ctx.getSystemService(AlarmManager::class.java).cancel(pi)
            pi.cancel()
        }
    }

    /** All upcoming rings: each enabled alarm's next time plus snoozes. */
    fun upcoming(ctx: Context, now: Long = System.currentTimeMillis()): List<Next> {
        val once = Store.stringSet(ctx, KEY_ONCE)
        val out = mutableListOf<Next>()
        for (a in Store.alarms(ctx)) {
            if (!a.on) continue
            if (a.oneTime && Schedule.onceKey(a) in once) continue
            val t = Schedule.nextFireMillis(a, now) ?: continue
            out += Next(t, a, false)
        }
        for ((at, a) in Store.snoozes(ctx)) if (at > now - 60_000) out += Next(at, a, true)
        return out.sortedBy { it.at }
    }

    fun nextAny(ctx: Context): Next? = upcoming(ctx).firstOrNull()

    fun scheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val old = Store.stringSet(ctx, KEY_ARMED)
        val armed = mutableSetOf<String>()
        val show = Notifs.openApp(ctx, "focus", null, 7001)
        for (n in upcoming(ctx)) {
            val code = ((if (n.snoozed) "s:" else "a:") + n.alarm.id).hashCode()
            val pi = fireIntent(ctx, code, n.alarm, n.snoozed)
            val at = maxOf(n.at, System.currentTimeMillis() + 1000)
            try {
                if (canExact(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            armed += code.toString()
        }
        (old - armed).forEach { it.toIntOrNull()?.let { c -> cancelCode(ctx, c) } }
        Store.putStringSet(ctx, KEY_ARMED, armed)
    }

    /** The page sent a fresh alarm list. */
    fun onSync(ctx: Context, alarmsJson: String) {
        Store.put(ctx, "alarms", alarmsJson)
        val alarms = Store.alarms(ctx)
        // forget "already used" marks for one-time alarms that were edited, re-enabled with a new time, or removed
        val keys = alarms.filter { it.oneTime && it.on }.map(Schedule::onceKey).toSet()
        Store.putStringSet(ctx, KEY_ONCE, Store.stringSet(ctx, KEY_ONCE).intersect(keys))
        // drop snoozes of alarms that no longer exist or were switched off
        val live = alarms.filter { it.on }.map { it.id }.toSet() + "test"
        Store.putSnoozes(ctx, Store.snoozes(ctx).filter { it.second.id in live })
        scheduleAll(ctx)
    }

    fun markFired(ctx: Context, a: Alarm, snoozed: Boolean) {
        if (snoozed) {
            Store.putSnoozes(ctx, Store.snoozes(ctx).filter { it.second.id != a.id })
        } else if (a.oneTime && a.id != "test") {
            Store.putStringSet(ctx, KEY_ONCE, Store.stringSet(ctx, KEY_ONCE) + Schedule.onceKey(a))
            Store.addEvent(ctx, JSONObject().put("type", "alarm_once_done").put("id", a.id))
        }
        scheduleAll(ctx)
    }

    fun snooze(ctx: Context, a: Alarm, minutes: Int = a.snooze) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        Store.putSnoozes(ctx, Store.snoozes(ctx).filter { it.second.id != a.id } + (at to a))
        scheduleAll(ctx)
    }

    fun test(ctx: Context, label: String, sound: String) {
        val a = Alarm("test", Schedule.hhmm(ZonedDateTime.now()), emptyList(), label, sound, 5, false, true)
        Store.putSnoozes(ctx, Store.snoozes(ctx).filter { it.second.id != "test" } + ((System.currentTimeMillis() + 10_000L) to a))
        scheduleAll(ctx)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = try { Alarm.fromJson(JSONObject(intent.getStringExtra("alarm") ?: return)) } catch (e: Exception) { return }
        val snoozed = intent.getBooleanExtra("snoozed", false)
        val svc = Intent(ctx, AlarmService::class.java).apply {
            action = AlarmService.ACTION_RING
            putExtra("alarm", a.toJson().toString())
        }
        ContextCompat.startForegroundService(ctx, svc)
        AlarmScheduler.markFired(ctx, a, snoozed)
        Widgets.updateAll(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifs.ensureChannels(ctx)
        AlarmScheduler.scheduleAll(ctx)
        Reminders.scheduleAll(ctx)
        Widgets.updateAll(ctx)
    }
}
