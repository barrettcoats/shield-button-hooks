package com.barrettcoats.buttonhooks

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one gesture does: a user-facing name, and either an HTTP request ([ActionType.WEBHOOK]) or
 * a local action whose [target] is a navigation id, media key id or package name.
 */
data class Action(
    val name: String = "",
    val url: String = "",
    val method: String = "GET",
    val body: String = "",
    val type: ActionType = ActionType.WEBHOOK,
    val target: String = "",
) {
    /** Whether this gesture does anything. [ActionType.NOTHING] still blocks the button. */
    val active: Boolean
        get() = when (type) {
            ActionType.WEBHOOK -> url.isNotEmpty()
            ActionType.NOTHING -> false
            else -> target.isNotEmpty()
        }

    /** Whether anything is saved for it at all. */
    val isEmpty: Boolean
        get() = name.isEmpty() && url.isEmpty() && body.isEmpty() && type == ActionType.WEBHOOK

    companion object {
        val METHODS = listOf("GET", "POST", "PUT", "DELETE")
    }
}

/** One hooked remote button and its per-gesture actions. */
data class Hook(
    val keyCode: Int,
    val label: String = "",
    val actions: Map<Gesture, Action> = emptyMap(),
    /** Extra request headers for every gesture of this button, one `Name: value` per line. */
    val headers: String = "",
) {
    /** [headers] parsed into pairs; lines without a colon are ignored. */
    fun headerPairs(): List<Pair<String, String>> = headers.lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }

    fun action(gesture: Gesture): Action = actions[gesture] ?: Action()

    fun actionName(gesture: Gesture): String = action(gesture).name.ifEmpty { gesture.label }

    val keyName: String get() = KeyEvent.keyCodeToString(keyCode)

    val title: String get() = label.ifEmpty { keyName }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", keyCode)
        put("label", label)
        if (headers.isNotEmpty()) put("headers", headers)
        put("actions", JSONObject().apply {
            actions.forEach { (gesture, action) ->
                put(gesture.id, JSONObject().apply {
                    put("name", action.name)
                    put("url", action.url)
                    if (action.method != "GET") put("method", action.method)
                    if (action.body.isNotEmpty()) put("body", action.body)
                    if (action.type != ActionType.WEBHOOK) put("type", action.type.id)
                    if (action.target.isNotEmpty()) put("target", action.target)
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): Hook {
            val actionsJson = json.optJSONObject("actions") ?: JSONObject()
            val actions = Gesture.entries.mapNotNull { gesture ->
                actionsJson.optJSONObject(gesture.id)?.let {
                    gesture to Action(
                        it.optString("name"),
                        it.optString("url"),
                        it.optString("method", "GET").ifEmpty { "GET" },
                        it.optString("body"),
                        ActionType.from(it.optString("type")),
                        it.optString("target"),
                    )
                }
            }.toMap()
            return Hook(json.getInt("key"), json.optString("label"), actions, json.optString("headers"))
        }

        fun listToJson(hooks: List<Hook>): String =
            JSONArray().apply { hooks.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<Hook> {
            val array = JSONArray(text)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
