package com.sardinetracker.sync

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts the assembled payload to the sardinetracker server with Bearer auth.
 * Uses plain HttpURLConnection to keep step 1 dependency-free; a later
 * iteration can swap in OkHttp + retry/queue if needed.
 */
object HealthSyncClient {

    data class Result(val ok: Boolean, val status: Int, val body: String)

    /**
     * Blocking — call from a background dispatcher (Dispatchers.IO).
     * [url] and [token] come from the user's [Settings]; they are never hardcoded.
     */
    fun post(payload: JSONObject, url: String, token: String): Result {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it)).use(BufferedReader::readText) } ?: ""
            Result(status in 200..299, status, body)
        } catch (e: Exception) {
            Result(false, -1, "network error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
