package com.barrettcoats.buttonhooks

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** What one gesture does: a user-facing name and the URL to GET. */
data class Action(val name: String = "", val url: String = "")

/** One hooked remote button and its per-gesture actions. */
data class Hook(
    val keyCode: Int,
    val label: String = "",
    val actions: Map<Gesture, Action> = emptyMap(),
) {
    fun action(gesture: Gesture): Action = actions[gesture] ?: Action()

    fun actionName(gesture: Gesture): String = action(gesture).name.ifEmpty { gesture.label }

    val keyName: String get() = KeyEvent.keyCodeToString(keyCode)

    val title: String get() = label.ifEmpty { keyName }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", keyCode)
        put("label", label)
        put("actions", JSONObject().apply {
            actions.forEach { (gesture, action) ->
                put(gesture.id, JSONObject().put("name", action.name).put("url", action.url))
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): Hook {
            val actionsJson = json.optJSONObject("actions") ?: JSONObject()
            val actions = Gesture.entries.mapNotNull { gesture ->
                actionsJson.optJSONObject(gesture.id)?.let {
                    gesture to Action(it.optString("name"), it.optString("url"))
                }
            }.toMap()
            return Hook(json.getInt("key"), json.optString("label"), actions)
        }

        fun listToJson(hooks: List<Hook>): String =
            JSONArray().apply { hooks.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<Hook> {
            val array = JSONArray(text)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
