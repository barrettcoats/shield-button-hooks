package com.barrettcoats.buttonhooks

import android.content.Context
import android.util.Log

/**
 * LineageOS for Android TV ships a KeyHandler (org.lineageos.lineageparts.atv.KeyHandler) that runs
 * before accessibility services. For each keycode in lineageos.platform:array/keyhandler_keycodes it
 * consumes the UP event and launches an app, so only DOWN events reach us for those keys.
 *
 * Builds with the upstream fix pass both events through when the key's app isn't installed. We can't
 * tell which build we're on, so a mapped key counts as UP-swallowed until we've seen an UP for it.
 */
object LineageKeyHandler {
    private const val TAG = "ButtonHooks"
    private const val PLATFORM_PACKAGE = "lineageos.platform"
    private const val SEEN_FILE = "lineage_keyhandler"
    private const val SEEN_KEY = "up_seen"

    private var mapped: Set<Int>? = null

    /** Keycodes LineageOS maps to apps. Empty when not on LineageOS. */
    private fun mappedKeys(context: Context): Set<Int> {
        mapped?.let { return it }
        val keys = try {
            val res = context.packageManager.getResourcesForApplication(PLATFORM_PACKAGE)
            val id = res.getIdentifier("keyhandler_keycodes", "array", PLATFORM_PACKAGE)
            if (id == 0) emptySet() else res.getIntArray(id).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        Log.i(TAG, "LineageOS KeyHandler maps keys: $keys")
        mapped = keys
        return keys
    }

    private fun seen(context: Context) =
        context.applicationContext.getSharedPreferences(SEEN_FILE, Context.MODE_PRIVATE)

    /** Keycodes whose UP events never reach accessibility services, as far as we know. */
    fun upSwallowedKeys(context: Context): Set<Int> {
        val seenUp = seen(context).getStringSet(SEEN_KEY, emptySet())!!.mapNotNull { it.toIntOrNull() }
        return mappedKeys(context) - seenUp.toSet()
    }

    /** Call for every UP that reaches us; returns true if this just proved the key isn't swallowed. */
    fun onUpSeen(context: Context, keyCode: Int): Boolean {
        if (keyCode !in mappedKeys(context)) return false
        val prefs = seen(context)
        val current = prefs.getStringSet(SEEN_KEY, emptySet())!!
        if (keyCode.toString() in current) return false
        prefs.edit().putStringSet(SEEN_KEY, current + keyCode.toString()).apply()
        Log.i(TAG, "Saw UP for LineageOS-mapped key $keyCode; hold is available")
        return true
    }
}
