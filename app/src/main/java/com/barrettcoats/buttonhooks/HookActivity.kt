package com.barrettcoats.buttonhooks

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Edits one hooked button: its name and what each gesture does. */
class HookActivity : Activity() {
    private lateinit var prefs: Prefs
    private var keyCode = -1
    private var removed = false

    private lateinit var label: EditText
    private lateinit var lastResult: TextView
    private lateinit var headers: EditText
    private lateinit var rows: Map<Gesture, Row>

    /** The views for one gesture, plus the choices that live in buttons rather than fields. */
    private inner class Row(gesture: Gesture, ids: IntArray) {
        val name: EditText = findViewById(ids[0])
        val typeButton: Button = findViewById(ids[1])
        val url: EditText = findViewById(ids[2])
        val targetButton: Button = findViewById(ids[3])
        val test: Button = findViewById(ids[4])
        val request: View = findViewById(ids[5])
        val methodButton: Button = findViewById(ids[6])
        val body: EditText = findViewById(ids[7])
        var type = ActionType.WEBHOOK
        var method = "GET"
        var target = ""

        init {
            typeButton.setOnClickListener {
                type = type.next()
                if (type != ActionType.WEBHOOK) target = ""
                show()
            }
            methodButton.setOnClickListener {
                val list = Action.METHODS
                method = list[(list.indexOf(method) + 1) % list.size]
                show()
            }
            targetButton.setOnClickListener { pickTarget(this) }
            test.setOnClickListener { test(gesture) }
        }

        fun load(action: Action) {
            name.setText(action.name)
            url.setText(action.url)
            body.setText(action.body)
            type = action.type
            method = action.method
            target = action.target
            show()
        }

        fun action() = Action(
            name.text.toString().trim(),
            url.text.toString().trim(),
            method,
            body.text.toString().trim(),
            type,
            if (type == ActionType.WEBHOOK || type == ActionType.NOTHING) "" else target,
        )

        fun show() {
            val webhook = type == ActionType.WEBHOOK
            typeButton.text = getString(R.string.type_label, type.label)
            url.visibility = if (webhook) View.VISIBLE else View.GONE
            request.visibility = if (webhook) View.VISIBLE else View.GONE
            targetButton.visibility = if (!webhook && type != ActionType.NOTHING) View.VISIBLE else View.GONE
            test.visibility = if (type == ActionType.NOTHING) View.INVISIBLE else View.VISIBLE
            methodButton.text = getString(R.string.method_label, method)
            targetButton.text = if (target.isEmpty()) getString(R.string.choose)
            else LocalActions.describe(this@HookActivity, Action(type = type, target = target))
            headersVisibility()
        }
    }

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
        headers = findViewById(R.id.headers)
        rows = mapOf(
            Gesture.SINGLE to Row(Gesture.SINGLE, intArrayOf(
                R.id.name_single, R.id.type_single, R.id.url_single, R.id.target_single,
                R.id.test_single, R.id.request_single, R.id.method_single, R.id.body_single,
            )),
            Gesture.DOUBLE to Row(Gesture.DOUBLE, intArrayOf(
                R.id.name_double, R.id.type_double, R.id.url_double, R.id.target_double,
                R.id.test_double, R.id.request_double, R.id.method_double, R.id.body_double,
            )),
            Gesture.HOLD to Row(Gesture.HOLD, intArrayOf(
                R.id.name_hold, R.id.type_hold, R.id.url_hold, R.id.target_hold,
                R.id.test_hold, R.id.request_hold, R.id.method_hold, R.id.body_hold,
            )),
        )

        findViewById<TextView>(R.id.hook_title).text = hook.title
        label.setText(hook.label)
        headers.setText(hook.headers)
        Gesture.entries.forEach { rows.getValue(it).load(hook.action(it)) }

        val downOnly = keyCode in LineageKeyHandler.upSwallowedKeys(this)
        val keyLine = "${hook.keyName} ($keyCode)"
        findViewById<TextView>(R.id.hook_key).text =
            if (downOnly) "$keyLine\n${getString(R.string.no_hold_note)}" else keyLine
        findViewById<View>(R.id.block_hold).visibility = if (downOnly) View.GONE else View.VISIBLE

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
        if (!removed && ::rows.isInitialized) save()
        super.onPause()
    }

    /** Headers only matter for webhooks; hide them when no gesture is one. */
    private fun headersVisibility() {
        if (!::rows.isInitialized || !::headers.isInitialized) return
        val any = rows.values.any { it.type == ActionType.WEBHOOK }
        val v = if (any) View.VISIBLE else View.GONE
        headers.visibility = v
        findViewById<View>(R.id.headers_label).visibility = v
    }

    private fun pickTarget(row: Row) {
        val choices = LocalActions.choices(this, row.type)
        if (choices.isEmpty()) {
            Toast.makeText(this, "Nothing to choose from", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = choices.indexOfFirst { it.id == row.target }
        AlertDialog.Builder(this)
            .setTitle(row.type.label)
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialog, which ->
                row.target = choices[which].id
                row.show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The hook as currently shown on screen. */
    private fun current(): Hook {
        val actions = Gesture.entries.associateWith { rows.getValue(it).action() }.filterValues { !it.isEmpty }
        return Hook(keyCode, label.text.toString().trim(), actions, headers.text.toString().trim())
    }

    private fun save() = prefs.saveHook(current())

    private fun test(gesture: Gesture) {
        val hook = current()
        val action = hook.action(gesture)
        val name = action.name.ifEmpty { LocalActions.describe(this, action) }
        if (!action.active) {
            Toast.makeText(this, "${gesture.label} doesn't do anything yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (action.type != ActionType.WEBHOOK) {
            // Navigation needs the accessibility service; the others work from here.
            val service = ButtonHookService.instance
            prefs.lastResult = "Test $name: " + LocalActions.run(this, service, action)
            return
        }
        lastResult.text = "Testing $name…"
        HttpSender.send(hook, gesture, prefs.timeoutMs) { result -> prefs.lastResult = "Test $name: $result" }
    }

    companion object {
        const val EXTRA_KEY = "key_code"
    }
}
