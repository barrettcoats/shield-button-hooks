package com.barrettcoats.buttonhooks

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent

/** Thin wrapper over the app's SharedPreferences, shared by the service and the settings screens. */
class Prefs(context: Context) {
    val raw: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var hooks: List<Hook>
        get() {
            val json = raw.getString(HOOKS, null) ?: return migrateSingleHook()
            return try {
                Hook.listFromJson(json)
            } catch (e: Exception) {
                Log.e("ButtonHooks", "Bad hooks JSON, ignoring", e)
                emptyList()
            }
        }
        set(value) = raw.edit().putString(HOOKS, Hook.listToJson(value)).apply()

    fun hook(keyCode: Int): Hook? = hooks.find { it.keyCode == keyCode }

    /** Replaces the hook with the same keycode, or appends it. */
    fun saveHook(hook: Hook) {
        val list = hooks.toMutableList()
        val index = list.indexOfFirst { it.keyCode == hook.keyCode }
        if (index >= 0) list[index] = hook else list.add(hook)
        hooks = list
    }

    fun removeHook(keyCode: Int) {
        hooks = hooks.filterNot { it.keyCode == keyCode }
    }

    var learning: Boolean
        get() = raw.getBoolean(LEARNING, false)
        set(value) = raw.edit().putBoolean(LEARNING, value).apply()

    /** Keycode the service just learned, for the settings screen to open; -1 when none. */
    var learnedKey: Int
        get() = raw.getInt(LEARNED_KEY, -1)
        set(value) = raw.edit().putInt(LEARNED_KEY, value).apply()

    var doubleMs: Long
        get() = raw.getLong(DOUBLE_MS, 300L)
        set(value) = raw.edit().putLong(DOUBLE_MS, value).apply()

    var holdMs: Long
        get() = raw.getLong(HOLD_MS, 500L)
        set(value) = raw.edit().putLong(HOLD_MS, value).apply()

    var badgeMode: BadgeMode
        get() = BadgeMode.entries.find { it.id == raw.getString(BADGE_MODE, null) } ?: BadgeMode.ALL
        set(value) = raw.edit().putString(BADGE_MODE, value.id).apply()

    /** Fire the remote's stored TV power code when the Shield sleeps or wakes. */
    var tvPower: Boolean
        get() = raw.getBoolean(TV_POWER, false)
        set(value) = raw.edit().putBoolean(TV_POWER, value).apply()

    /** Bluetooth address of the remote that fires TV power, or null for any connected one. */
    var tvPowerRemote: String?
        get() = raw.getString(TV_POWER_REMOTE, null)
        set(value) = raw.edit().putString(TV_POWER_REMOTE, value).apply()

    var lastResult: String
        get() = raw.getString(LAST_RESULT, "") ?: ""
        set(value) = raw.edit().putString(LAST_RESULT, value).apply()

    /**
     * Version 0.1 stored a single button as flat keys. Turn that into the first hook, or start with
     * the Shield remote's Netflix button (BUTTON_4 on LineageOS) on a fresh install.
     */
    private fun migrateSingleHook(): List<Hook> {
        val keyCode = raw.getInt(OLD_KEY_CODE, KeyEvent.KEYCODE_BUTTON_4)
        val actions = Gesture.entries.associateWith { gesture ->
            Action(
                raw.getString("name_" + gesture.id, "") ?: "",
                raw.getString("url_" + gesture.id, "") ?: "",
            )
        }.filterValues { it.url.isNotEmpty() || it.name.isNotEmpty() }
        val label = if (keyCode == KeyEvent.KEYCODE_BUTTON_4) "Netflix" else ""
        val migrated = listOf(Hook(keyCode, label, actions))
        raw.edit().apply {
            putString(HOOKS, Hook.listToJson(migrated))
            remove(OLD_KEY_CODE)
            Gesture.entries.forEach { remove("name_" + it.id); remove("url_" + it.id) }
        }.apply()
        return migrated
    }

    companion object {
        const val FILE = "button_hooks"
        const val HOOKS = "hooks"
        const val LEARNING = "learning"
        const val LEARNED_KEY = "learned_key"
        const val DOUBLE_MS = "double_ms"
        const val HOLD_MS = "hold_ms"
        const val BADGE_MODE = "badge_mode"
        const val LAST_RESULT = "last_result"
        const val TV_POWER = "tv_power"
        const val TV_POWER_REMOTE = "tv_power_remote"
        private const val OLD_KEY_CODE = "key_code"
    }
}

enum class BadgeMode(val id: String) {
    ALL("all"),
    ERRORS("errors"),
    OFF("off");

    fun next(): BadgeMode = entries[(ordinal + 1) % entries.size]
}
