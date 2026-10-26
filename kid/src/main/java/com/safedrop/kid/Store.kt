package com.safedrop.kid

import androidx.compose.runtime.mutableStateListOf
import com.safedrop.core.Cloud
import com.safedrop.core.FilePolicy
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A file transfer waiting for the parent's decision. IN = received (quarantined), OUT = child wants to send. */
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

    fun toCloud(status: String = "pending") = JSONObject().apply {
        put("direction", direction); put("peer", peer); put("fileName", fileName)
        put("size", size); put("time", time); put("status", status)
    }

    companion object {
        fun fromJson(o: JSONObject) = Pending(
            o.getString("id"), o.getString("direction"), o.getString("peer"),
            o.optString("endpointId"), o.getString("fileName"),
            o.getLong("size"), o.getString("path"), o.getLong("time"),
        )
    }
}

object ParentRules {
    /** Returns a reason if the peer must be refused, else null. */
    fun checkPeer(name: String, p: FilePolicy): String? {
        if (!p.enabled) return null
        if (p.trustedOnly && name !in p.trusted) return "not a trusted device"
        return null
    }

    /** Returns a reason if the file must be refused, else null. */
    fun checkFile(name: String, size: Long, p: FilePolicy): String? {
        if (!p.enabled) return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in p.blockedExt) return "blocked file type (.$ext)"
        if (size > p.maxSizeMb * 1024L * 1024L) return "larger than ${p.maxSizeMb} MB"
        return null
    }
}

/** Pending file approvals (persisted locally, mirrored to the cloud) and the activity log (cloud only). */
object Store {
    val pending = mutableStateListOf<Pending>()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(KidState.prefs.getString("pending", "[]"))
            for (i in 0 until a.length()) pending.add(Pending.fromJson(a.getJSONObject(i)))
        }
    }

    private fun save() {
        val a = JSONArray(); pending.forEach { a.put(it.toJson()) }
        KidState.prefs.edit().putString("pending", a.toString()).apply()
    }

    fun addPending(p: Pending) {
        pending.add(p); save()
        if (KidState.configured()) KidState.scope.launch { Cloud.put(KidState.path("pending/${p.id}"), p.toCloud()) }
    }

    fun removePending(p: Pending) {
        pending.remove(p); save()
        if (KidState.configured()) KidState.scope.launch { Cloud.delete(KidState.path("pending/${p.id}")) }
    }

    fun addLog(text: String) {
        if (!KidState.configured()) return
        KidState.scope.launch {
            Cloud.post(KidState.path("log"), JSONObject().put("t", System.currentTimeMillis()).put("m", text))
        }
    }
}
