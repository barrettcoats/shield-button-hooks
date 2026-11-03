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
        hooksList.removeAllViews()
        val hooks = prefs.hooks
        if (hooks.isEmpty()) {
            hooksList.addView(TextView(this).apply {
                setText(R.string.no_hooks)
                setTextColor(getColor(R.color.mid))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(12), 0, 0)
            })
            return
        }
        hooks.forEach { hook -> hooksList.addView(hookRow(hook)) }
    }

    private fun hookRow(hook: Hook) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, 0)

        addView(TextView(context).apply {
            text = "${hook.title}\n${summary(hook)}"
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(Button(context, null, 0, R.style.FlatButton).apply {
            setText(R.string.edit)
            setOnClickListener { openHook(hook.keyCode) }
        })
    }

    private fun summary(hook: Hook): String {
        val downOnly = hook.keyCode in LineageKeyHandler.upSwallowedKeys(this)
        val parts = Gesture.entries
            .filterNot { it == Gesture.HOLD && downOnly }
            .map { gesture ->
                val action = hook.action(gesture)
                "${gesture.label}: " + when {
                    action.url.isEmpty() -> "—"
                    action.name.isNotEmpty() -> action.name
                    else -> "✓"
                }
            }
        return "${hook.keyName} (${hook.keyCode})   " + parts.joinToString("   ")
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
