package com.barrettcoats.buttonhooks

import android.os.Handler

enum class Gesture(val id: String, val label: String) {
    SINGLE("single", "Single press"),
    DOUBLE("double", "Double press"),
    HOLD("hold", "Hold"),
}

/**
 * Turns raw DOWN/UP events for one button into single, double and hold gestures.
 *
 * - Hold fires while the button is still down, once [holdMs] has passed.
 * - Double fires on the second press's DOWN if it lands within [doubleMs] of the first release.
 * - Single fires [doubleMs] after release, or straight away when double press is unused.
 *
 * When [downOnly] is true the button never sends UP, so hold is impossible and single/double are
 * told apart by the time between DOWNs alone.
 *
 * All calls must come from [handler]'s thread.
 */
class GestureDetector(
    private val handler: Handler,
    private val doubleMs: () -> Long,
    private val holdMs: () -> Long,
    private val doubleEnabled: () -> Boolean,
    private val downOnly: () -> Boolean,
    private val onGesture: (Gesture) -> Unit,
) {
    private var holdFired = false
    private var awaitingSecond = false
    private var inSecondPress = false

    private val holdRunnable = Runnable {
        holdFired = true
        onGesture(Gesture.HOLD)
    }

    private val singleRunnable = Runnable {
        awaitingSecond = false
        onGesture(Gesture.SINGLE)
    }

    fun onDown() {
        if (awaitingSecond) {
            handler.removeCallbacks(singleRunnable)
            awaitingSecond = false
            inSecondPress = !downOnly()
            onGesture(Gesture.DOUBLE)
            return
        }
        if (downOnly()) {
            awaitForSecondOrFireSingle()
            return
        }
        holdFired = false
        handler.postDelayed(holdRunnable, holdMs())
    }

    fun onUp() {
        if (inSecondPress) {
            inSecondPress = false
            return
        }
        handler.removeCallbacks(holdRunnable)
        if (holdFired) return
        awaitForSecondOrFireSingle()
    }

    private fun awaitForSecondOrFireSingle() {
        if (doubleEnabled()) {
            awaitingSecond = true
            handler.postDelayed(singleRunnable, doubleMs())
        } else {
            onGesture(Gesture.SINGLE)
        }
    }

    fun reset() {
        handler.removeCallbacks(holdRunnable)
        handler.removeCallbacks(singleRunnable)
        holdFired = false
        awaitingSecond = false
        inSecondPress = false
    }
}
