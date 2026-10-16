package ru.chikizis.ritm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** What the page can ask of the phone. Exposed to JavaScript as window.RitmNative. */
class Bridge(private val act: MainActivity) {
    private val ctx = act.applicationContext

    /** The page sends alarms, reminders and widget data after every change. */
    @JavascriptInterface
    fun sync(json: String) {
        try {
            val o = JSONObject(json)
            o.optString("accent").takeIf { it.startsWith("#") }?.let { Store.put(ctx, "accent", it) }
            o.optJSONArray("alarms")?.let { AlarmScheduler.onSync(ctx, it.toString()) }
            o.optJSONArray("reminders")?.let { Reminders.onSync(ctx, it.toString()) }
            o.optJSONObject("widget")?.let { Widgets.onSync(ctx, it.toString()) } ?: Widgets.updateAll(ctx)
        } catch (_: Exception) {}
    }

    @JavascriptInterface fun takeEvents(): String = Store.takeEvents(ctx)

    @JavascriptInterface
    fun getStatus(): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val notif = Notifs.canPost(ctx) && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val next = AlarmScheduler.nextAny(ctx)
        return JSONObject()
            .put("notif", notif)
            .put("exact", AlarmScheduler.canExact(ctx))
            .put("fullscreen", Notifs.canFullScreen(ctx))
            .put("battery", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            .put("next", next?.at ?: 0)
            .put("version", appVersion())
            .put("sdk", Build.VERSION.SDK_INT)
            .toString()
    }

    @JavascriptInterface
    fun appVersion(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    @JavascriptInterface
    fun openSetting(which: String) {
        val pkg = Uri.parse("package:" + ctx.packageName)
        if (which == "notif" && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // ask in-app twice; after that Android stops showing the dialog, so open the settings page instead
            val asked = (Store.get(ctx, "notif_asked") ?: "0").toIntOrNull() ?: 0
            if (asked < 2) {
                Store.put(ctx, "notif_asked", (asked + 1).toString())
                act.runOnUiThread { act.notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                return
            }
        }
        val i = when (which) {
            "notif" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            "exact" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        } ?: return
        try { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    @JavascriptInterface fun testAlarm(label: String, sound: String) = AlarmScheduler.test(ctx, label, sound)

    /** kind: "ringtone" (phone melodies) or "file" (any audio file). Answers via window.__ritmSoundPicked(id, name). */
    @JavascriptInterface fun pickSound(kind: String) = act.pickSound(kind)
    @JavascriptInterface fun previewSound(sound: String) = Sounds.preview(ctx, sound)
    @JavascriptInterface fun stopPreview() = Sounds.stopPreview()

    @JavascriptInterface fun saveFile(name: String, data: String) = act.saveFile(name, data)

    @JavascriptInterface fun keepScreenOn(on: Boolean) = act.keepScreenOn(on)

    @JavascriptInterface
    fun setSystemBars(color: String, dark: Boolean) {
        val c = try { Color.parseColor(color) } catch (_: Exception) { return }
        act.setBars(c, dark)
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        if (!url.startsWith("https://")) return
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}
