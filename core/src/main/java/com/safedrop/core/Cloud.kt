package com.safedrop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tiny Firebase Realtime Database REST client. No SDK or google-services.json needed. */
object Cloud {
    @Volatile var baseUrl: String = ""

    private fun exec(method: String, path: String, body: String?, query: String = "", override: String? = null): String? {
        return try {
            val full = baseUrl.trimEnd('/') + "/" + path.trim('/') + ".json" + (if (query.isEmpty()) "" else "?$query")
            val c = URL(full).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (override != null) c.setRequestProperty("X-HTTP-Method-Override", override)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            if (code in 200..299) (text ?: "") else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getObj(path: String, query: String = ""): JSONObject? = withContext(Dispatchers.IO) {
        val t = exec("GET", path, null, query) ?: return@withContext null
        try { JSONObject(t) } catch (e: Exception) { null }
    }

    suspend fun getArr(path: String): JSONArray? = withContext(Dispatchers.IO) {
        val t = exec("GET", path, null) ?: return@withContext null
        try { JSONArray(t) } catch (e: Exception) { null }
    }

    /** True if the request reached the server (even if the value is null). */
    suspend fun reachable(path: String): Boolean = withContext(Dispatchers.IO) { exec("GET", path, null, "shallow=true") != null }

    suspend fun put(path: String, value: Any): Boolean = withContext(Dispatchers.IO) { exec("PUT", path, value.toString()) != null }

    suspend fun patch(path: String, value: JSONObject): Boolean =
        withContext(Dispatchers.IO) { exec("POST", path, value.toString(), override = "PATCH") != null }

    suspend fun post(path: String, value: JSONObject): Boolean = withContext(Dispatchers.IO) { exec("POST", path, value.toString()) != null }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) { exec("DELETE", path, null) != null }
}
