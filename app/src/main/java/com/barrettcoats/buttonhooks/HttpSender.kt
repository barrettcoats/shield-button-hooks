package com.barrettcoats.buttonhooks

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Sends HTTP requests off the main thread and reports a one-line result back on it. */
object HttpSender {
    private const val TAG = "ButtonHooks"

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Replaces `{gesture}` and `{key}` in a URL or body. */
    fun expand(template: String, gesture: Gesture, keyCode: Int): String =
        template.replace("{gesture}", gesture.id).replace("{key}", keyCode.toString())

    fun send(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: String,
        timeoutMs: Int,
        onResult: (String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                if (body.isNotEmpty() && method != "GET") {
                    if (headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
                        val trimmed = body.trimStart()
                        val json = trimmed.startsWith("{") || trimmed.startsWith("[")
                        conn.setRequestProperty("Content-Type", if (json) "application/json" else "text/plain; charset=utf-8")
                    }
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                try {
                    val code = conn.responseCode
                    "HTTP $code ${conn.responseMessage ?: ""}".trim()
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "Failed: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
            }
            // Never log headers: they often hold tokens.
            Log.i(TAG, "$method $url -> $result")
            main.post { onResult(result) }
        }
    }

    /** Sends [hook]'s request for [gesture]. */
    fun send(hook: Hook, gesture: Gesture, timeoutMs: Int, onResult: (String) -> Unit) {
        val action = hook.action(gesture)
        send(
            action.method,
            expand(action.url, gesture, hook.keyCode),
            hook.headerPairs(),
            expand(action.body, gesture, hook.keyCode),
            timeoutMs,
            onResult,
        )
    }
}
