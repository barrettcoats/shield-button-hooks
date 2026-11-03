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
    private lateinit var bodyFields: Map<Gesture, EditText>
    private lateinit var methodButtons: Map<Gesture, Button>
    private lateinit var headers: EditText
    private val methods = mutableMapOf<Gesture, String>()

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
        bodyFields = mapOf(
            Gesture.SINGLE to findViewById(R.id.body_single),
            Gesture.DOUBLE to findViewById(R.id.body_double),
            Gesture.HOLD to findViewById(R.id.body_hold),
        )
        methodButtons = mapOf(
            Gesture.SINGLE to findViewById(R.id.method_single),
            Gesture.DOUBLE to findViewById(R.id.method_double),
            Gesture.HOLD to findViewById(R.id.method_hold),
        )
        headers = findViewById(R.id.headers)
        headers.setText(hook.headers)

        findViewById<TextView>(R.id.hook_title).text = hook.title
        label.setText(hook.label)
        Gesture.entries.forEach { gesture ->
            nameFields.getValue(gesture).setText(hook.action(gesture).name)
            urlFields.getValue(gesture).setText(hook.action(gesture).url)
            bodyFields.getValue(gesture).setText(hook.action(gesture).body)
            methods[gesture] = hook.action(gesture).method
            showMethod(gesture)
            methodButtons.getValue(gesture).setOnClickListener {
                val list = Action.METHODS
                methods[gesture] = list[(list.indexOf(methods[gesture]) + 1) % list.size]
                showMethod(gesture)
            }
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

    private fun showMethod(gesture: Gesture) {
        methodButtons.getValue(gesture).text = getString(R.string.method_label, methods[gesture] ?: "GET")
    }

    /** The hook as currently shown on screen. */
    private fun current(): Hook {
        val actions = Gesture.entries.associateWith { gesture ->
            Action(
                nameFields.getValue(gesture).text.toString().trim(),
                urlFields.getValue(gesture).text.toString().trim(),
                methods[gesture] ?: "GET",
                bodyFields.getValue(gesture).text.toString().trim(),
            )
        }.filterValues { it.name.isNotEmpty() || it.url.isNotEmpty() || it.body.isNotEmpty() }
        return Hook(keyCode, label.text.toString().trim(), actions, headers.text.toString().trim())
    }

    private fun save() = prefs.saveHook(current())

    private fun test(gesture: Gesture) {
        val hook = current()
        if (hook.action(gesture).url.isEmpty()) {
            Toast.makeText(this, "No URL for ${gesture.label}", Toast.LENGTH_SHORT).show()
            return
        }
        val name = hook.actionName(gesture)
        lastResult.text = "Testing $name…"
        HttpSender.send(hook, gesture, prefs.timeoutMs) { result -> prefs.lastResult = "Test $name: $result" }
    }

    companion object {
        const val EXTRA_KEY = "key_code"
    }
}
