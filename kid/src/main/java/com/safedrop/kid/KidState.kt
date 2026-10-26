package com.safedrop.kid

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.safedrop.core.Cloud
import com.safedrop.core.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import java.security.MessageDigest

/** Process-wide state for the kid's device: pairing config + latest rules from the parent. */
object KidState {
    lateinit var prefs: SharedPreferences
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var control: Control = Control()

    val dbUrl: String get() = prefs.getString("db", "") ?: ""
    val code: String get() = prefs.getString("code", "") ?: ""
    val childName: String get() = prefs.getString("name", "") ?: ""
    fun configured() = dbUrl.isNotBlank() && code.isNotBlank()
    fun path(sub: String) = "pairs/$code/$sub"

    fun init(app: Application) {
        if (::prefs.isInitialized) return
        prefs = app.getSharedPreferences("kid", Context.MODE_PRIVATE)
        Cloud.baseUrl = dbUrl
        runCatching { control = Control.fromJson(JSONObject(prefs.getString("control", "{}")!!)) }
    }

    fun saveConfig(db: String, code: String, name: String) {
        prefs.edit().putString("db", db.trim()).putString("code", code.trim().uppercase()).putString("name", name.trim()).apply()
        Cloud.baseUrl = db.trim()
    }

    fun updateControl(c: Control) {
        control = c
        prefs.edit().putString("control", c.toJson().toString()).apply()
    }

    private fun hashPin(p: String): String =
        MessageDigest.getInstance("SHA-256").digest("safedropkid:$p".toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin() = prefs.contains("pin")
    fun setPin(p: String) { prefs.edit().putString("pin", hashPin(p)).apply() }
    fun checkPin(p: String) = prefs.getString("pin", null) == hashPin(p)

    fun clearConfig() {
        prefs.edit().remove("db").remove("code").remove("name").remove("pin").remove("control").apply()
        Cloud.baseUrl = ""
        control = Control()
    }
}
