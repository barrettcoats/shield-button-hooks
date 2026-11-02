package com.barrettcoats.buttonhooks

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Edits one hooked button: its name and the action for each gesture. */
class HookActivity : Activity() {
    private lateinit var prefs: Prefs
    private var keyCode = -1
    private var removed = false

    private lateinit var label: EditText
    private lateinit var lastResult: TextView
    private lateinit var nameFields: Map<Gesture, EditText>
    private lateinit var urlFields: Map<Gesture, EditText>

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.LAST_RESULT) lastResult.text = prefs.lastResult
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hook)
        prefs = Prefs(this)
        keyCode = intent.getIntExtra(EXTRA_KEY, -1)
        val hook = prefs.hook(keyCode) ?: run {
            finish()
            return
        }

        label = findViewById(R.id.hook_label)
        lastResult = findViewById(R.id.last_result)
        nameFields = mapOf(
            Gesture.SINGLE to findViewById(R.id.name_single),
            Gesture.DOUBLE to findViewById(R.id.name_double),
            Gesture.HOLD to findViewById(R.id.name_hold),
        )
        urlFields = mapOf(
            Gesture.SINGLE to findViewById(R.id.url_single),
            Gesture.DOUBLE to findViewById(R.id.url_double),
            Gesture.HOLD to findViewById(R.id.url_hold),
        )

        findViewById<TextView>(R.id.hook_title).text = hook.title
        label.setText(hook.label)
        Gesture.entries.forEach { gesture ->
            nameFields.getValue(gesture).setText(hook.action(gesture).name)
            urlFields.getValue(gesture).setText(hook.action(gesture).url)
        }

        val downOnly = keyCode in LineageKeyHandler.upSwallowedKeys(this)
        val keyLine = "${hook.keyName} ($keyCode)"
        findViewById<TextView>(R.id.hook_key).text =
            if (downOnly) "$keyLine\n${getString(R.string.no_hold_note)}" else keyLine
        findViewById<View>(R.id.block_hold).visibility = if (downOnly) View.GONE else View.VISIBLE

        mapOf(
            Gesture.SINGLE to R.id.test_single,
            Gesture.DOUBLE to R.id.test_double,
            Gesture.HOLD to R.id.test_hold,
        ).forEach { (gesture, id) ->
            findViewById<Button>(id).setOnClickListener { test(gesture) }
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            save()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.remove).setOnClickListener {
            removed = true
            prefs.removeHook(keyCode)
            Toast.makeText(this, R.string.removed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (!removed && !isFinishingWithoutHook()) save()
        super.onPause()
    }

    private fun isFinishingWithoutHook() = !::label.isInitialized

    private fun save() {
        val actions = Gesture.entries.associateWith { gesture ->
            Action(nameFields.getValue(gesture).text.toString().trim(), urlFields.getValue(gesture).text.toString().trim())
        }.filterValues { it.name.isNotEmpty() || it.url.isNotEmpty() }
        prefs.saveHook(Hook(keyCode, label.text.toString().trim(), actions))
    }

    private fun test(gesture: Gesture) {
        val template = urlFields.getValue(gesture).text.toString().trim()
        if (template.isEmpty()) {
            Toast.makeText(this, "No URL for ${gesture.label}", Toast.LENGTH_SHORT).show()
            return
        }
        val url = HttpSender.expand(template, gesture, keyCode)
        val name = nameFields.getValue(gesture).text.toString().trim().ifEmpty { gesture.label }
        lastResult.text = "Testing $name…"
        HttpSender.get(url) { result -> prefs.lastResult = "Test $name: $result" }
    }

    companion object {
        const val EXTRA_KEY = "key_code"
    }
}
