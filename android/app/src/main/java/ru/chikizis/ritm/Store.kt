package ru.chikizis.ritm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent store shared by the app, the alarm, reminders and widgets. */
object Store {
    private fun p(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("ritm", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): String? = p(ctx).getString(key, null)
    fun put(ctx: Context, key: String, value: String?) { p(ctx).edit().putString(key, value).apply() }

    fun alarms(ctx: Context): List<Alarm> = Alarm.listFrom(get(ctx, "alarms"))
    fun accent(ctx: Context): Int = try {
        android.graphics.Color.parseColor(get(ctx, "accent") ?: "#3346F0")
    } catch (e: Exception) { 0xFF3346F0.toInt() }

    fun stringSet(ctx: Context, key: String): MutableSet<String> = p(ctx).getStringSet(key, emptySet())!!.toMutableSet()
    fun putStringSet(ctx: Context, key: String, v: Set<String>) { p(ctx).edit().putStringSet(key, v).apply() }

    /** Snoozed alarms waiting to ring again: [{at, alarm}] */
    fun snoozes(ctx: Context): MutableList<Pair<Long, Alarm>> {
        val out = mutableListOf<Pair<Long, Alarm>>()
        try {
            val a = JSONArray(get(ctx, "snoozes") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += o.getLong("at") to Alarm.fromJson(o.getJSONObject("alarm"))
            }
        } catch (_: Exception) {}
        return out
    }
    fun putSnoozes(ctx: Context, list: List<Pair<Long, Alarm>>) {
        val a = JSONArray()
        list.forEach { (at, al) -> a.put(JSONObject().put("at", at).put("alarm", al.toJson())) }
        put(ctx, "snoozes", a.toString())
    }

    /** Things that happened while the page was closed; the page applies them on next open. */
    @Synchronized fun addEvent(ctx: Context, ev: JSONObject) {
        val a = try { JSONArray(get(ctx, "events") ?: "[]") } catch (_: Exception) { JSONArray() }
        a.put(ev)
        put(ctx, "events", a.toString())
    }
    @Synchronized fun takeEvents(ctx: Context): String {
        val s = get(ctx, "events") ?: "[]"
        put(ctx, "events", "[]")
        return s
    }
}
