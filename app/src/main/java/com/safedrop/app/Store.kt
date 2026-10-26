package com.safedrop.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Parental rules. Edited only from the PIN-protected Parent tab. */
data class Policy(
    val enabled: Boolean = true,
    val approveIncoming: Boolean = true,
    val approveOutgoing: Boolean = true,
    val blockedExt: Set<String> = setOf("apk", "xapk", "apks", "exe", "bat", "sh", "jar", "msi"),
    val maxSizeMb: Int = 50,
    val trustedOnly: Boolean = false,
    val trusted: Set<String> = emptySet(),
    val quietEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 6,
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("approveIncoming", approveIncoming)
        put("approveOutgoing", approveOutgoing)
        put("blockedExt", JSONArray(blockedExt.toList()))
        put("maxSizeMb", maxSizeMb)
        put("trustedOnly", trustedOnly)
        put("trusted", JSONArray(trusted.toList()))
        put("quietEnabled", quietEnabled)
        put("quietStartHour", quietStartHour)
        put("quietEndHour", quietEndHour)
    }.toString()

    companion object {
        private fun set(a: JSONArray?, def: Set<String>): Set<String> =
            if (a == null) def else (0 until a.length()).map { a.getString(it) }.toSet()

        fun fromJson(s: String): Policy {
            val d = Policy()
            val o = JSONObject(s)
            return Policy(
                enabled = o.optBoolean("enabled", d.enabled),
                approveIncoming = o.optBoolean("approveIncoming", d.approveIncoming),
                approveOutgoing = o.optBoolean("approveOutgoing", d.approveOutgoing),
                blockedExt = set(o.optJSONArray("blockedExt"), d.blockedExt),
                maxSizeMb = o.optInt("maxSizeMb", d.maxSizeMb),
                trustedOnly = o.optBoolean("trustedOnly", d.trustedOnly),
                trusted = set(o.optJSONArray("trusted"), d.trusted),
                quietEnabled = o.optBoolean("quietEnabled", d.quietEnabled),
                quietStartHour = o.optInt("quietStartHour", d.quietStartHour),
                quietEndHour = o.optInt("quietEndHour", d.quietEndHour),
            )
        }
    }
}

/** A transfer waiting for a parent's decision. IN = received (quarantined), OUT = child wants to send. */
data class Pending(
    val id: String,
    val direction: String,
    val peer: String,
    val endpointId: String,
    val fileName: String,
    val size: Long,
    val path: String,
    val time: Long,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("direction", direction); put("peer", peer)
        put("endpointId", endpointId); put("fileName", fileName)
        put("size", size); put("path", path); put("time", time)
    }

    companion object {
        fun fromJson(o: JSONObject) = Pending(
            o.getString("id"), o.getString("direction"), o.getString("peer"),
            o.optString("endpointId"), o.getString("fileName"),
            o.getLong("size"), o.getString("path"), o.getLong("time"),
        )
    }
}

data class ActivityEntry(val time: Long, val text: String)

object ParentRules {
    fun inQuietHours(p: Policy): Boolean {
        if (!p.enabled || !p.quietEnabled) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (p.quietStartHour <= p.quietEndHour) h in p.quietStartHour until p.quietEndHour
        else h >= p.quietStartHour || h < p.quietEndHour
    }

    /** Returns a reason string if the peer must be refused, else null. */
    fun checkPeer(name: String, p: Policy): String? {
        if (!p.enabled) return null
        if (inQuietHours(p)) return "quiet hours"
        if (p.trustedOnly && name !in p.trusted) return "not a trusted device"
        return null
    }

    /** Returns a reason string if the file must be refused, else null. */
    fun checkFile(name: String, size: Long, p: Policy): String? {
        if (!p.enabled) return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in p.blockedExt) return "blocked file type (.$ext)"
        if (size > p.maxSizeMb * 1024L * 1024L) return "larger than ${p.maxSizeMb} MB"
        return null
    }
}

object Store {
    private lateinit var sp: SharedPreferences
    val policy = mutableStateOf(Policy())
    val pending = mutableStateListOf<Pending>()
    val log = mutableStateListOf<ActivityEntry>()

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("safedrop", Context.MODE_PRIVATE)
        runCatching { policy.value = Policy.fromJson(sp.getString("policy", "{}")!!) }
        runCatching {
            val a = JSONArray(sp.getString("pending", "[]"))
            for (i in 0 until a.length()) pending.add(Pending.fromJson(a.getJSONObject(i)))
        }
        runCatching {
            val a = JSONArray(sp.getString("log", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                log.add(ActivityEntry(o.getLong("t"), o.getString("m")))
            }
        }
    }

    fun setPolicy(p: Policy) {
        policy.value = p
        sp.edit().putString("policy", p.toJson()).apply()
    }

    fun addPending(p: Pending) { pending.add(p); savePending() }
    fun removePending(p: Pending) { pending.remove(p); savePending() }
    private fun savePending() {
        val a = JSONArray(); pending.forEach { a.put(it.toJson()) }
        sp.edit().putString("pending", a.toString()).apply()
    }

    fun addLog(text: String) {
        log.add(0, ActivityEntry(System.currentTimeMillis(), text))
        while (log.size > 200) log.removeAt(log.size - 1)
        saveLog()
    }
    fun clearLog() { log.clear(); saveLog() }
    private fun saveLog() {
        val a = JSONArray()
        log.forEach { a.put(JSONObject().put("t", it.time).put("m", it.text)) }
        sp.edit().putString("log", a.toString()).apply()
    }

    // ---- Parent PIN ----
    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("safedrop:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun hasPin() = sp.contains("pin")
    fun setPin(pin: String) { sp.edit().putString("pin", hash(pin)).apply() }
    fun checkPin(pin: String) = sp.getString("pin", null) == hash(pin)

    fun fmt(t: Long): String = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(t))
}
