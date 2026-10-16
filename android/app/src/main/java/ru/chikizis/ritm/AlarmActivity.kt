package ru.chikizis.ritm

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference
import java.time.ZonedDateTime

/** Full-screen ringing alarm, shown over the lock screen. */
class AlarmActivity : ComponentActivity() {
    private val anims = mutableListOf<android.animation.Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_alarm)

        val a = AlarmService.current
        if (a == null) { finishAndRemoveTask(); return }
        AlarmService.activity = WeakReference(this)

        // background tinted with the colour the user picked for buttons
        val accent = Store.accent(this)
        findViewById<View>(R.id.alarm_root).background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = resources.displayMetrics.heightPixels * 0.8f
            setGradientCenter(0.5f, 0.36f)
            colors = intArrayOf(
                ColorUtils.blendARGB(accent, Color.BLACK, 0.25f),
                ColorUtils.blendARGB(accent, Color.parseColor("#06071A"), 0.72f),
                Color.parseColor("#06071A"),
            )
        }

        val now = ZonedDateTime.now()
        findViewById<TextView>(R.id.time).text = Schedule.hhmm(now)
        findViewById<TextView>(R.id.label).text = a.label.ifBlank { "Будильник" }
        findViewById<TextView>(R.id.date).text =
            Schedule.weekday(now.toLocalDate()).replaceFirstChar { it.uppercase() } + ", " + Schedule.dayMonth(now.toLocalDate())
        findViewById<Button>(R.id.snooze).apply {
            text = "Отложить на ${a.snooze} мин"
            setOnClickListener { AlarmService.send(this@AlarmActivity, AlarmService.ACTION_SNOOZE); finishAndRemoveTask() }
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_STOP); finishAndRemoveTask()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* the alarm is dismissed with the buttons only */ }
        })
        animate()
    }

    private fun animate() {
        listOf(R.id.wave1, R.id.wave2, R.id.wave3).forEachIndexed { i, id ->
            val v = findViewById<View>(id)
            v.alpha = 0f
            val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 0.4f, 2.4f)
            val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.4f, 2.4f)
            val al = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0f)
            listOf(sx, sy, al).forEach { it.repeatCount = ValueAnimator.INFINITE; it.duration = 2400 }
            AnimatorSet().apply {
                playTogether(sx, sy, al)
                interpolator = DecelerateInterpolator()
                startDelay = i * 800L
                start()
                anims += this
            }
        }
        val bell = findViewById<ImageView>(R.id.bell)
        bell.pivotY = 6f * resources.displayMetrics.density
        ObjectAnimator.ofFloat(bell, View.ROTATION, 0f, 18f, -16f, 12f, -8f, 4f, 0f, 0f, 0f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; start(); anims += this
        }
        val time = findViewById<TextView>(R.id.time)
        time.alpha = 0f; time.translationY = 40f
        time.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(DecelerateInterpolator()).start()
    }

    override fun onResume() {
        super.onResume()
        if (AlarmService.current == null) finishAndRemoveTask()
    }

    override fun onDestroy() {
        anims.forEach { it.cancel() }
        if (AlarmService.activity?.get() === this) AlarmService.activity = null
        super.onDestroy()
    }
}
