package ru.chikizis.ritm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

object Notifs {
    const val CH_ALARM = "alarm_ring_v1"
    const val CH_REMIND = "reminders_v1"
    const val CH_INFO = "info_v1"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(CH_ALARM, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Звонящий будильник на весь экран"
            setSound(null, null) // the alarm service plays the melody itself
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val remind = NotificationChannel(CH_REMIND, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Напоминания о событиях и задачах"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        val info = NotificationChannel(CH_INFO, "Служебные", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Отложенный будильник и прочее"
        }
        nm.createNotificationChannels(listOf(alarm, remind, info))
    }

    /** Opens the app on a given tab (and optionally the "add" sheet). */
    fun openApp(ctx: Context, tab: String, add: String? = null, req: Int = tab.hashCode()): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = "ru.chikizis.ritm.OPEN.$tab.${add ?: ""}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("tab", tab)
            if (add != null) putExtra("add", add)
        }
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canPost(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled()
    }

    fun canFullScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
}
