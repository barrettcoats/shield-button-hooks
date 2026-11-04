package com.barrettcoats.buttonhooks

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    private lateinit var serviceStatus: TextView
    private lateinit var hooksList: LinearLayout
    private lateinit var addHook: Button
    private lateinit var learnStatus: TextView
    private lateinit var lastResult: TextView
    private lateinit var doubleMs: EditText
    private lateinit var holdMs: EditText
    private lateinit var timeoutMs: EditText
    private lateinit var badgeMode: Button
    private lateinit var tvPower: Button
    private lateinit var tvPowerRemote: Button

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.HOOKS -> showHooks()
            Prefs.LEARNING -> onLearningChanged()
            Prefs.LAST_RESULT -> lastResult.text = prefs.lastResult
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        serviceStatus = findViewById(R.id.service_status)
        hooksList = findViewById(R.id.hooks_list)
        addHook = findViewById(R.id.add_hook)
        learnStatus = findViewById(R.id.learn_status)
        lastResult = findViewById(R.id.last_result)
        doubleMs = findViewById(R.id.double_ms)
        holdMs = findViewById(R.id.hold_ms)
        timeoutMs = findViewById(R.id.timeout_ms)
        badgeMode = findViewById(R.id.badge_mode)
        tvPower = findViewById(R.id.tv_power)
        tvPowerRemote = findViewById(R.id.tv_power_remote)
        showTvPower()

        // Cycles: any connected remote → each paired remote → back to any.
        tvPowerRemote.setOnClickListener {
            val addresses = listOf<String?>(null) + RemoteIr.pairedRemotes(this).map { it.address }
            val next = (addresses.indexOf(prefs.tvPowerRemote) + 1) % addresses.size
            prefs.tvPowerRemote = addresses[next]
            showTvPower()
        }

        tvPower.setOnClickListener {
            if (!prefs.tvPower && !RemoteIr.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT)
                return@setOnClickListener
            }
            // Cycles: off → on/off codes → toggle code → off.
            when {
                !prefs.tvPower -> { prefs.tvPowerToggle = false; prefs.tvPower = true }
                !prefs.tvPowerToggle -> prefs.tvPowerToggle = true
                else -> { prefs.tvPower = false; prefs.tvPowerToggle = false }
            }
            showTvPower()
        }

        findViewById<Button>(R.id.tv_power_test).setOnClickListener {
            if (!RemoteIr.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT)
                return@setOnClickListener
            }
            val remote = prefs.tvPowerRemote
            if (prefs.tvPowerToggle) {
                RemoteIr.emit(this, RemoteIr.SLOT_POWER_TOGGLE, remote) { result ->
                    Toast.makeText(this, "TV power toggle: $result", Toast.LENGTH_SHORT).show()
                }
            } else {
                RemoteIr.emit(this, RemoteIr.SLOT_POWER_OFF, remote) { off ->
                    Toast.makeText(this, "TV off: $off", Toast.LENGTH_SHORT).show()
                    // Give the TV time to finish switching off before it'll accept ON.
                    tvPower.postDelayed({
                        RemoteIr.emit(this, RemoteIr.SLOT_POWER_ON, remote) { on ->
                            Toast.makeText(this, "TV on: $on", Toast.LENGTH_SHORT).show()
                        }
                    }, 8000)
                }
            }
        }

        doubleMs.setText(prefs.doubleMs.toString())
        holdMs.setText(prefs.holdMs.toString())
        timeoutMs.setText(prefs.timeoutMs.toString())
        showBadgeMode()

        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "No accessibility settings screen on this device", Toast.LENGTH_LONG).show()
            }
        }

        addHook.setOnClickListener {
            prefs.learnedKey = -1
            prefs.learning = !prefs.learning
        }

        badgeMode.setOnClickListener {
            prefs.badgeMode = prefs.badgeMode.next()
            showBadgeMode()
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
        showService()
        showHooks()
        showLearning()
        lastResult.text = prefs.lastResult
        // A timing field can end up with focus when the screen opens (seen on LineageOS),
        // and on a TV the D-pad then only moves the cursor inside it.
        if (currentFocus == null || currentFocus is EditText) addHook.requestFocus()
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
        prefs.learning = false
        save()
        super.onPause()
    }

    private fun save() {
        doubleMs.text.toString().toLongOrNull()?.let { prefs.doubleMs = it.coerceIn(100, 2000) }
        holdMs.text.toString().toLongOrNull()?.let { prefs.holdMs = it.coerceIn(200, 5000) }
        timeoutMs.text.toString().toIntOrNull()?.let { prefs.timeoutMs = it.coerceIn(500, 30000) }
    }

    /** The service clears the learning flag once it has a key; open that key's editor. */
    private fun onLearningChanged() {
        showLearning()
        val learned = prefs.learnedKey
        if (!prefs.learning && learned != -1) {
            prefs.learnedKey = -1
            openHook(learned)
        }
    }

    private fun openHook(keyCode: Int) {
        startActivity(Intent(this, HookActivity::class.java).putExtra(HookActivity.EXTRA_KEY, keyCode))
    }

    private fun showLearning() {
        val learning = prefs.learning
        addHook.setText(if (learning) R.string.cancel else R.string.add_button)
        learnStatus.text = if (learning) getString(R.string.learning) else ""
    }

    private fun showHooks() {
        // Read first: on a fresh install, reading saves an empty list, which calls showHooks again.
        val hooks = prefs.hooks
        hooksList.removeAllViews()
        if (hooks.isEmpty()) {
            hooksList.addView(TextView(this).apply {
                setText(R.string.no_hooks)
                setTextColor(getColor(R.color.mid))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(12), 0, 0)
            })
        }
        val editButtons = hooks.map { hook -> hookRow(hook).also { hooksList.addView(it.view) }.editButton }
        linkFocus(editButtons)
    }

    /**
     * The Edit buttons sit on the right and Add button on the left, so Android's
     * nearest-view D-pad search jumps from the last Edit straight to the timing
     * fields (also on the right) and Add button can't be reached with a remote.
     * Chain them explicitly: accessibility → each Edit → Add button → timing.
     */
    private fun linkFocus(editButtons: List<Button>) {
        val openAccessibility = findViewById<Button>(R.id.open_accessibility)
        val chain = listOf<Button>(openAccessibility) + editButtons + addHook
        chain.zipWithNext { above, below ->
            above.nextFocusDownId = below.id
            below.nextFocusUpId = above.id
        }
        addHook.nextFocusDownId = doubleMs.id
        doubleMs.nextFocusUpId = addHook.id
    }

    private class HookRow(val view: LinearLayout, val editButton: Button)

    private fun hookRow(hook: Hook): HookRow {
        val edit = Button(this, null, 0, R.style.FlatButton).apply {
            id = android.view.View.generateViewId()
            setText(R.string.edit)
            setOnClickListener { openHook(hook.keyCode) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)

            addView(TextView(context).apply {
                text = "${hook.title}\n${summary(hook)}"
                setTextColor(getColor(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(dp(4).toFloat(), 1f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(edit)
        }
        return HookRow(row, edit)
    }

    private fun summary(hook: Hook): String {
        val downOnly = hook.keyCode in LineageKeyHandler.upSwallowedKeys(this)
        val gestures = Gesture.entries.filterNot { it == Gesture.HOLD && downOnly }
        val key = "${hook.keyName} (${hook.keyCode})   "
        if (gestures.none { hook.action(it).active }) return key + getString(R.string.disabled_summary)
        val parts = gestures.map { gesture ->
            val action = hook.action(gesture)
            "${gesture.label}: " + if (action.active && action.name.isNotEmpty()) action.name else LocalActions.describe(this, action)
        }
        return key + parts.joinToString("   ")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_BT && RemoteIr.hasPermission(this)) {
            prefs.tvPower = true
            showTvPower()
        }
    }

    private fun showTvPower() {
        tvPower.setText(
            when {
                !prefs.tvPower -> R.string.tv_power_off
                prefs.tvPowerToggle -> R.string.tv_power_toggle
                else -> R.string.tv_power_on
            }
        )
        val chosen = RemoteIr.pairedRemotes(this).firstOrNull { it.address == prefs.tvPowerRemote }
        tvPowerRemote.text = if (chosen == null) getString(R.string.tv_power_remote_any)
        else getString(R.string.tv_power_remote_one, RemoteIr.label(chosen))
    }

    private fun showBadgeMode() {
        badgeMode.setText(
            when (prefs.badgeMode) {
                BadgeMode.ALL -> R.string.badges_all
                BadgeMode.ERRORS -> R.string.badges_errors
                BadgeMode.OFF -> R.string.badges_off
            }
        )
    }

    private fun showService() {
        val component = ComponentName(this, ButtonHookService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { ComponentName.unflattenFromString(it) == component } == true
        serviceStatus.text = if (enabled) "Service is ON" else "Service is OFF: enable Button Hooks in accessibility settings"
        serviceStatus.setTextColor(getColor(if (enabled) R.color.text else R.color.accent))
    }

    companion object {
        private const val REQUEST_BT = 1
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()
}
