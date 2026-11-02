package com.barrettcoats.buttonhooks

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Fires GET requests off the main thread and reports a one-line result back on it. */
object HttpSender {
    private const val TAG = "ButtonHooks"
    private const val TIMEOUT_MS = 4000

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun expand(template: String, gesture: Gesture, keyCode: Int): String =
        template.replace("{gesture}", gesture.id).replace("{key}", keyCode.toString())

    fun get(url: String, onResult: (String) -> Unit) {
        executor.execute {
            val result = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                try {
                    val code = conn.responseCode
                    "HTTP $code ${conn.responseMessage ?: ""}".trim()
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "Failed: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
            }
            Log.i(TAG, "GET $url -> $result")
            main.post { onResult(result) }
        }
    }
}
