package ru.chikizis.ritm

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color

/** The colour of the app icon and the widgets. Each colour is a launcher alias; exactly one is enabled. */
object IconColors {
    // name to (main colour, second gradient colour) — same list as make_android_icons.py and native.js
    val ALL = linkedMapOf(
        "cobalt" to ("#3346F0" to "#8E3FE0"), "ocean" to ("#0A8DB0" to "#3346F0"), "mint" to ("#16A085" to "#2EE6C8"),
        "green" to ("#1E9970" to "#5E9400"), "sunset" to ("#FF8A3D" to "#D93A40"), "amber" to ("#E0A000" to "#E06A0A"),
        "rose" to ("#E0548A" to "#B42FB0"), "red" to ("#D93A40" to "#8E1C3A"), "violet" to ("#8E3FE0" to "#5B3DF5"),
        "graphite" to ("#4A5270" to "#15172C"),
    )

    fun current(ctx: Context): String = Store.get(ctx, "icon_color")?.takeIf { it in ALL } ?: "cobalt"
    fun main(ctx: Context): Int = Color.parseColor(ALL[current(ctx)]!!.first)
    fun second(ctx: Context): Int = Color.parseColor(ALL[current(ctx)]!!.second)
    fun style(ctx: Context): String = Store.get(ctx, "widget_style") ?: "color"

    private fun alias(ctx: Context, name: String) =
        ComponentName(ctx.packageName, "ru.chikizis.ritm.Icon" + name.replaceFirstChar { it.uppercase() })

    /** Switch the launcher icon. The launcher may take a few seconds to redraw it. */
    fun set(ctx: Context, name: String) {
        if (name !in ALL) return
        val pm = ctx.packageManager
        // enable the new one first so the app never has zero launcher entries
        pm.setComponentEnabledSetting(alias(ctx, name), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (n in ALL.keys) if (n != name) {
            pm.setComponentEnabledSetting(alias(ctx, n),
                if (n == "cobalt") PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP)
        }
        if (name == "cobalt") pm.setComponentEnabledSetting(alias(ctx, "cobalt"), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        Store.put(ctx, "icon_color", name)
        Widgets.updateAll(ctx)
    }

    fun setStyle(ctx: Context, style: String) {
        if (style != "color" && style != "plain") return
        Store.put(ctx, "widget_style", style)
        Widgets.updateAll(ctx)
    }
}
