package com.barrettcoats.buttonhooks

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent

/** What a gesture does. Stored by [id]; unknown ids read back as [WEBHOOK]. */
enum class ActionType(val id: String, val label: String) {
    WEBHOOK("webhook", "Webhook"),
    NOTHING("nothing", "Nothing"),
    NAV("nav", "Navigation"),
    MEDIA("media", "Media key"),
    APP("app", "Open app");

    fun next(): ActionType = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): ActionType = entries.find { it.id == id } ?: WEBHOOK
    }
}

/** A choice for [ActionType.NAV] or [ActionType.MEDIA]: stored [id], shown [label]. */
data class Choice(val id: String, val label: String)

/**
 * Actions that run on the device instead of over HTTP. Android doesn't let apps inject arbitrary
 * key presses, so these are limited to what an accessibility service can do: global navigation
 * actions, media keys through [AudioManager], and opening apps.
 */
object LocalActions {
    /** Navigation choices and their AccessibilityService global action, with the minimum SDK. */
    private data class Nav(val choice: Choice, val action: Int, val minSdk: Int = 1)

    private val NAV = listOf(
        Nav(Choice("back", "Back"), AccessibilityService.GLOBAL_ACTION_BACK),
        Nav(Choice("home", "Home"), AccessibilityService.GLOBAL_ACTION_HOME),
        Nav(Choice("recents", "Recent apps"), AccessibilityService.GLOBAL_ACTION_RECENTS),
        Nav(Choice("notifications", "Notifications"), AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
        Nav(Choice("quick_settings", "Quick settings"), AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
        Nav(Choice("dpad_up", "D-pad up"), 16, 33),
        Nav(Choice("dpad_down", "D-pad down"), 17, 33),
        Nav(Choice("dpad_left", "D-pad left"), 18, 33),
        Nav(Choice("dpad_right", "D-pad right"), 19, 33),
        Nav(Choice("dpad_center", "D-pad select"), 20, 33),
    )

    private val MEDIA = listOf(
        Choice("play_pause", "Play/pause") to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        Choice("play", "Play") to KeyEvent.KEYCODE_MEDIA_PLAY,
        Choice("pause", "Pause") to KeyEvent.KEYCODE_MEDIA_PAUSE,
        Choice("stop", "Stop") to KeyEvent.KEYCODE_MEDIA_STOP,
        Choice("next", "Next") to KeyEvent.KEYCODE_MEDIA_NEXT,
        Choice("previous", "Previous") to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        Choice("fast_forward", "Fast-forward") to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        Choice("rewind", "Rewind") to KeyEvent.KEYCODE_MEDIA_REWIND,
    )

    fun navChoices(): List<Choice> = NAV.filter { Build.VERSION.SDK_INT >= it.minSdk }.map { it.choice }

    fun mediaChoices(): List<Choice> = MEDIA.map { it.first }

    /** Launchable apps (TV launcher entries first), sorted by name. */
    fun appChoices(context: Context): List<Choice> {
        val pm = context.packageManager
        val seen = mutableSetOf<String>()
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        }.mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || !seen.add(pkg)) null
            else Choice(pkg, info.loadLabel(pm).toString())
        }.sortedBy { it.label.lowercase() }
    }

    fun choices(context: Context, type: ActionType): List<Choice> = when (type) {
        ActionType.NAV -> navChoices()
        ActionType.MEDIA -> mediaChoices()
        ActionType.APP -> appChoices(context)
        else -> emptyList()
    }

    /** A short description such as "Home", "Play/pause" or "Open Jellyfin". */
    fun describe(context: Context, action: Action): String = when (action.type) {
        ActionType.WEBHOOK -> if (action.url.isEmpty()) "—" else "${action.method} webhook"
        ActionType.NOTHING -> "Nothing"
        ActionType.NAV -> NAV.find { it.choice.id == action.target }?.choice?.label ?: "—"
        ActionType.MEDIA -> MEDIA.find { it.first.id == action.target }?.first?.label ?: "—"
        ActionType.APP -> if (action.target.isEmpty()) "—" else "Open " + appLabel(context, action.target)
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        "$pkg (not installed)"
    }

    /**
     * Runs a non-webhook [action] from [service]. Returns a short result such as "ok" or an error.
     * Navigation needs the service itself; the others only need a context.
     */
    fun run(context: Context, service: AccessibilityService?, action: Action): String = when (action.type) {
        ActionType.NOTHING, ActionType.WEBHOOK -> "ok"
        ActionType.NAV -> {
            val nav = NAV.find { it.choice.id == action.target } ?: return "no action chosen"
            when {
                Build.VERSION.SDK_INT < nav.minSdk -> "needs Android ${if (nav.minSdk == 33) "13" else nav.minSdk}"
                service == null -> "service is off"
                service.performGlobalAction(nav.action) -> "ok"
                else -> "refused"
            }
        }
        ActionType.MEDIA -> {
            val code = MEDIA.find { it.first.id == action.target }?.second ?: return "no key chosen"
            val audio = context.getSystemService(AudioManager::class.java)
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            "ok"
        }
        ActionType.APP -> {
            val pm = context.packageManager
            val intent = pm.getLeanbackLaunchIntentForPackage(action.target)
                ?: pm.getLaunchIntentForPackage(action.target)
            if (intent == null) {
                "app not installed"
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "ok"
            }
        }
    }
}
