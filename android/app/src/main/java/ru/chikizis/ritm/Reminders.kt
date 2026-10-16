package ru.chikizis.ritm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reminders for events and tasks. The page computes the next two weeks of reminder moments
 * and hands them over; they fire as notifications even when the app is closed.
 */
object Reminders {
    private const val KEY = "reminders"
    private const val KEY_ARMED = "armed_reminder_codes"
    private const val MAX = 80

    private fun code(id: String) = ("r:$id").hashCode()

    private fun pending(ctx: Context, id: String, o: JSONObject?, flags: Int): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "ru.chikizis.ritm.REMIND.${code(id)}"
            if (o != null) putExtra("r", o.toString())
        }
        return PendingIntent.getBroadcast(ctx, code(id), i, PendingIntent.FLAG_IMMUTABLE or flags)
    }

    fun onSync(ctx: Context, json: String) {
        Store.put(ctx, KEY, json)
        scheduleAll(ctx)
    }

    fun scheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val list = try { JSONArray(Store.get(ctx, KEY) ?: "[]") } catch (e: Exception) { JSONArray() }
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optLong("at") > now - 30_000 && it.optString("id").isNotEmpty() }
            .sortedBy { it.optLong("at") }.take(MAX)
        val old = Store.stringSet(ctx, KEY_ARMED)
        val armed = mutableSetOf<String>()
        for (o in items) {
            val id = o.getString("id")
            val pi = pending(ctx, id, o, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            val at = maxOf(o.getLong("at"), now + 1000)
            try {
                if (AlarmScheduler.canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            armed += id
        }
        for (id in old - armed) {
            pending(ctx, id, null, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        Store.putStringSet(ctx, KEY_ARMED, armed)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    companion object { const val ACTION_DONE = "ru.chikizis.ritm.TASK_DONE" }

    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (intent.action == ACTION_DONE) {
            val taskId = intent.getStringExtra("taskId") ?: return
            val date = intent.getStringExtra("date") ?: return
            Store.addEvent(ctx, JSONObject().put("type", "task_done").put("id", taskId).put("date", date))
            Widgets.markTaskDone(ctx, taskId, date)
            nm.cancel(intent.getIntExtra("notifId", 0))
            return
        }
        val o = try { JSONObject(intent.getStringExtra("r") ?: return) } catch (e: Exception) { return }
        Notifs.ensureChannels(ctx)
        val id = o.optString("id")
        val notifId = id.hashCode()
        val tab = o.optString("tab", "plan")
        val b = NotificationCompat.Builder(ctx, Notifs.CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Store.accent(ctx))
            .setContentTitle(o.optString("title", "Напоминание"))
            .setContentText(o.optString("body", ""))
            .setStyle(NotificationCompat.BigTextStyle().bigText(o.optString("body", "")))
            .setCategory(if (tab == "tasks") NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(Notifs.openApp(ctx, tab, null, notifId))
        val taskId = o.optString("taskId", "")
        if (taskId.isNotEmpty()) {
            val done = Intent(ctx, ReminderReceiver::class.java).apply {
                action = ACTION_DONE
                putExtra("taskId", taskId); putExtra("date", o.optString("date")); putExtra("notifId", notifId)
            }
            b.addAction(0, "Выполнено", PendingIntent.getBroadcast(ctx, notifId, done,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (Notifs.canPost(ctx)) nm.notify(notifId, b.build())
    }
}
