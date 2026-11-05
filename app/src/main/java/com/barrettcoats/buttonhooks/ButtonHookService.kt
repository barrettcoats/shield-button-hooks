package com.barrettcoats.buttonhooks

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.text.DateFormat
import java.util.Date

/**
 * Filters key events system-wide. Every hooked button is swallowed so its normal action never
 * runs, and its gestures are turned into HTTP requests.
 */
class ButtonHookService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var overlay: FeedbackOverlay

    private var hooks: Map<Int, Hook> = emptyMap()
    private val detectors = mutableMapOf<Int, GestureDetector>()

    /** Keycode whose UP still needs swallowing after its DOWN was used for learning. */
    private var swallowUpFor: Int? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.HOOKS -> reloadHooks()
            Prefs.TV_POWER -> updateTvPower()
        }
    }

    /**
     * Sleep/wake of the Shield turns the TV off/on through the remote's stored IR power codes:
     * the discrete OFF/ON codes three times each, like NVIDIA's firmware, or the toggle once.
     *
     * By default the TV is only turned off when the Shield was put to sleep with the power
     * button. A sleep from the idle timer, or from HDMI-CEC (a console or other device took over
     * the TV), leaves the TV alone, so it can't go dark in the middle of something on another
     * input. "TV off: whenever the Shield sleeps" ([Prefs.tvOffAlways]) turns it off every time. Waking
     * always fires ON (harmless if the TV is already on); the toggle fires on wake only if this
     * app toggled the TV off, since a blind toggle could turn it off instead.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            val toggle = prefs.tvPowerToggle
            val remote = prefs.tvPowerRemote
            if (on) {
                when {
                    !toggle -> {
                        Log.i(TAG, "${intent.action}: TV power on")
                        RemoteIr.emit(context, RemoteIr.SLOT_POWER_ON, remote, times = 3)
                    }
                    tvTurnedOff -> {
                        Log.i(TAG, "${intent.action}: TV power toggle (back on)")
                        RemoteIr.emit(context, RemoteIr.SLOT_POWER_TOGGLE, remote)
                    }
                    else -> Log.i(TAG, "${intent.action}: TV wasn't turned off by us; no toggle")
                }
                tvTurnedOff = false
                return
            }
            Thread {
                val reason = SleepReason.last(context)
                // "Every sleep" fires regardless. Otherwise only the power button does; an
                // unreadable reason (no DUMP grant) leaves the TV alone, since a TV left on is a
                // small annoyance and one switched off in the middle of a game on another input
                // isn't.
                val fire = prefs.tvOffAlways || reason == SleepReason.POWER_BUTTON
                main.post {
                    Log.i(TAG, "${intent.action} (sleep reason: ${reason ?: "unknown"}): " +
                        if (fire) "TV power ${if (toggle) "toggle" else "off"}" else "leaving the TV alone")
                    if (fire) {
                        if (toggle) RemoteIr.emit(context, RemoteIr.SLOT_POWER_TOGGLE, remote)
                        else RemoteIr.emit(context, RemoteIr.SLOT_POWER_OFF, remote, times = 3)
                    }
                    tvTurnedOff = fire
                }
            }.start()
        }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Whether the last sleep turned the TV off (used to pair the toggle code on wake). */
    private var tvTurnedOff = false
    private var screenReceiverRegistered = false

    private fun updateTvPower() {
        val want = prefs.tvPower
        if (want && !screenReceiverRegistered) {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
            screenReceiverRegistered = true
        } else if (!want && screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
    }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        overlay = FeedbackOverlay(this)
        reloadHooks()
        updateTvPower()
        prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun reloadHooks() {
        hooks = prefs.hooks.associateBy { it.keyCode }
        detectors.values.forEach { it.reset() }
        detectors.clear()
        Log.i(TAG, "Hooking ${hooks.values.joinToString { "${it.title} (${it.keyCode})" }}")
    }

    private fun detectorFor(keyCode: Int) = detectors.getOrPut(keyCode) {
        GestureDetector(
            handler = handler,
            doubleMs = { prefs.doubleMs },
            holdMs = { prefs.holdMs },
            doubleEnabled = { hooks[keyCode]?.action(Gesture.DOUBLE)?.active == true },
            downOnly = { keyCode in LineageKeyHandler.upSwallowedKeys(this) },
            onGesture = { gesture -> fire(keyCode, gesture) },
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "key ${KeyEvent.keyCodeToString(code)} ($code) scan=${event.scanCode} action=${event.action} repeat=${event.repeatCount} source=0x${Integer.toHexString(event.source)}")
        }

        // The first UP from a key we thought was UP-swallowed belongs to a press the detector already
        // handled in DOWN-only mode; feeding it in would start a second single press.
        if (event.action == KeyEvent.ACTION_UP && LineageKeyHandler.onUpSeen(this, code)) {
            return code in hooks
        }

        if (swallowUpFor == code && event.action == KeyEvent.ACTION_UP) {
            swallowUpFor = null
            return true
        }

        if (prefs.learning && event.action == KeyEvent.ACTION_DOWN && code !in NAVIGATION_KEYS) {
            if (prefs.hook(code) == null) prefs.saveHook(Hook(code))
            prefs.learnedKey = code
            prefs.learning = false
            swallowUpFor = code
            Log.i(TAG, "Learned ${KeyEvent.keyCodeToString(code)} ($code), scan code ${event.scanCode}")
            overlay.show("Hooked ${KeyEvent.keyCodeToString(code)}")
            return true
        }

        if (code !in hooks) return false

        // Swallow repeats too, so a held button never leaks through.
        if (event.repeatCount == 0) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> detectorFor(code).onDown()
                KeyEvent.ACTION_UP -> detectorFor(code).onUp()
            }
        }
        return true
    }

    private fun fire(keyCode: Int, gesture: Gesture) {
        val hook = hooks[keyCode] ?: return
        val action = hook.action(gesture)
        val name = action.name.ifEmpty { LocalActions.describe(this, action) }
        if (!action.active) {
            Log.i(TAG, "${hook.title} ${gesture.id}: does nothing")
            return
        }
        if (action.type != ActionType.WEBHOOK) {
            val result = LocalActions.run(this, this, action)
            Log.i(TAG, "${hook.title} ${gesture.id}: $name -> $result")
            when {
                result != "ok" && prefs.badgeMode != BadgeMode.OFF -> overlay.show("✗ $name: $result", error = true)
                result == "ok" && prefs.badgeMode == BadgeMode.ALL -> overlay.show("✓ $name")
            }
            return
        }
        if (prefs.badgeMode == BadgeMode.ALL) overlay.show("$name…")
        HttpSender.send(hook, gesture, prefs.timeoutMs) { result ->
            val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            prefs.lastResult = "$time  ${hook.title} · $name: $result"
            val ok = result.startsWith("HTTP 2")
            when {
                !ok && prefs.badgeMode != BadgeMode.OFF -> overlay.show("✗ $name: $result", error = true)
                ok && prefs.badgeMode == BadgeMode.ALL -> overlay.show("✓ $name")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        detectors.values.forEach { it.reset() }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (::prefs.isInitialized) prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::overlay.isInitialized) overlay.hide()
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ButtonHooks"

        /** The running service, for actions that need it (navigation) from the settings screens. */
        @Volatile
        var instance: ButtonHookService? = null
            private set

        /** Never learn these, or the remote could lose the ability to drive the UI. */
        private val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
        )
    }
}
