package ru.chikizis.ritm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.time.ZonedDateTime

/** Rings the alarm: plays the melody in a loop, vibrates and shows the full-screen alarm over the lock screen. */
class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "ru.chikizis.ritm.RING"
        const val ACTION_STOP = "ru.chikizis.ritm.STOP"
        const val ACTION_SNOOZE = "ru.chikizis.ritm.SNOOZE"
        const val NOTIF_ID = 4242
        private const val MAX_RING_MS = 10 * 60_000L

        @Volatile var current: Alarm? = null
        @Volatile var ringStarted: Long = 0
        var activity: WeakReference<AlarmActivity>? = null

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
        }

        fun soundRes(ctx: Context, name: String): Uri? {
            val id = ctx.resources.getIdentifier("alarm_$name", "raw", ctx.packageName)
            return if (id != 0) Uri.parse("android.resource://${ctx.packageName}/$id") else null
        }
    }

    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var volume = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val a = try { Alarm.fromJson(JSONObject(intent.getStringExtra("alarm") ?: "{}")) } catch (e: Exception) { null }
                if (a == null) { stopSelf(); return START_NOT_STICKY }
                ring(a)
            }
            ACTION_STOP -> finish(snooze = false)
            ACTION_SNOOZE -> finish(snooze = true)
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ring(a: Alarm) {
        Notifs.ensureChannels(this)
        stopSound()
        current = a
        ringStarted = System.currentTimeMillis()

        val n = buildNotification(a)
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            // could not go foreground (very rare); still show the notification so the alarm is not lost
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        }

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ritm:alarm").apply { acquire(MAX_RING_MS + 60_000L) }

        startSound(a)
        startVibration()
        // also try to open the alarm screen directly (works when the app may start activities)
        try { startActivity(alarmScreenIntent()) } catch (_: Exception) {}

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (current != null) finish(snooze = true) }, MAX_RING_MS)
    }

    private fun alarmScreenIntent() = Intent(this, AlarmActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
    }

    private fun buildNotification(a: Alarm): Notification {
        val full = PendingIntent.getActivity(this, 501, alarmScreenIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(action: String, req: Int) = PendingIntent.getService(this, req,
            Intent(this, AlarmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, Notifs.CH_ALARM)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Store.accent(this))
            .setContentTitle("Будильник " + Schedule.hhmm(ZonedDateTime.now()))
            .setContentText(a.label.ifBlank { "Пора вставать" })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Отложить на ${a.snooze} мин", act(ACTION_SNOOZE, 502))
            .addAction(0, "Выключить", act(ACTION_STOP, 503))
            .build()
    }

    private fun startSound(a: Alarm) {
        val uri = soundRes(this, a.sound)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                volume = if (a.grad) 0.12f else 1f
                setVolume(volume, volume)
                start()
            }
            if (a.grad) rampVolume()
        } catch (e: Exception) {
            player = null
        }
    }

    private fun rampVolume() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val p = player ?: return
                volume = (volume + 0.03f).coerceAtMost(1f) // ~30 seconds to full volume
                try { p.setVolume(volume, volume) } catch (_: Exception) { return }
                if (volume < 1f) handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

    private fun startVibration() {
        try {
            val v = vibrator() ?: return
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 1200), 0)
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (_: Exception) {}
    }

    private fun stopSound() {
        handler.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator()?.cancel() } catch (_: Exception) {}
    }

    private fun finish(snooze: Boolean) {
        val a = current
        stopSound()
        current = null
        if (a != null && snooze) {
            AlarmScheduler.snooze(this, a)
            val at = ZonedDateTime.now().plusMinutes(a.snooze.toLong())
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(4243, NotificationCompat.Builder(this, Notifs.CH_INFO)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(Store.accent(this))
                .setContentTitle("Будильник отложен до ${Schedule.hhmm(at)}")
                .setContentText(a.label.ifBlank { "Позвоню снова через ${a.snooze} мин" })
                .setTimeoutAfter(a.snooze * 60_000L)
                .setContentIntent(Notifs.openApp(this, "focus"))
                .setAutoCancel(true)
                .build())
        } else {
            getSystemService(NotificationManager::class.java).cancel(4243)
        }
        activity?.get()?.finishAndRemoveTask()
        activity = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        Widgets.updateAll(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }
}
