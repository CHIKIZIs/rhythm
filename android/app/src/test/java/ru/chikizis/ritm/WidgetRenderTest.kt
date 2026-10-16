package ru.chikizis.ritm

import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Builds every widget exactly the way the home screen does (RemoteViews.apply), in every colour and style.
 * A layout element Android does not allow in widgets, or a bad setter, fails here instead of on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRenderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun seed(withData: Boolean) {
        val today = LocalDate.now().toString()
        Store.put(ctx, "alarms", """[{"id":"a1","time":"07:00","days":[1,2,3,4,5],"label":"Подъём","sound":"bells","snooze":10,"grad":true,"on":true}]""")
        Store.put(ctx, "widget", if (!withData) "{}" else """{"days":{"$today":{
            "events":[{"s":"08:00","e":"09:00","t":"Зарядка","c":"#3346F0"},{"s":"12:00","e":"13:00","t":"Обед","c":"#EA6430"},{"s":"23:30","e":"","t":"Сон","c":"#16A085"}],
            "tasks":[{"id":"t1","t":"Сдать проект","p":1,"d":false},{"id":"t2","t":"Купить хлеб","p":2,"d":true},{"id":"t3","t":"Позвонить","p":3,"d":false}]}}}""")
    }

    private fun render(v: RemoteViews) {
        val view = v.apply(ctx, FrameLayout(ctx))
        assertNotNull(view)
    }

    @Test
    fun everyWidgetRendersInEveryColourAndStyle() {
        for (withData in listOf(true, false)) {
            seed(withData)
            for (style in listOf("color", "plain")) {
                Store.put(ctx, "widget_style", style)
                for (icon in IconColors.ALL.keys) {
                    Store.put(ctx, "icon_color", icon)
                    render(Widgets.alarmViews(ctx))
                    render(Widgets.tasksViews(ctx))
                    render(Widgets.planViews(ctx))
                    render(Widgets.todayViews(ctx))
                }
            }
        }
    }
}
