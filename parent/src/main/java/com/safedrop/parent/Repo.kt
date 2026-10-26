package com.safedrop.parent

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedrop.core.Cloud
import com.safedrop.core.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class ParentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

data class FileRequest(val id: String, val direction: String, val peer: String, val fileName: String, val size: Long, val time: Long)
data class LogLine(val time: Long, val text: String)

object Repo {
    private lateinit var prefs: SharedPreferences
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var status by mutableStateOf<JSONObject?>(null)
    var requests by mutableStateOf<List<FileRequest>>(emptyList())
    var log by mutableStateOf<List<LogLine>>(emptyList())
    var control by mutableStateOf(Control())
    var reachable by mutableStateOf<Boolean?>(null)
    private var controlLoaded = false

    val db: String get() = prefs.getString("db", "") ?: ""
    val code: String get() = prefs.getString("code", "") ?: ""
    val kidName: String get() = prefs.getString("name", "") ?: ""
    fun configured() = db.isNotBlank() && code.isNotBlank()
    fun path(sub: String) = "pairs/$code/$sub"

    fun init(app: Application) {
        if (::prefs.isInitialized) return
        prefs = app.getSharedPreferences("parent", Context.MODE_PRIVATE)
        Cloud.baseUrl = db
    }

    fun save(db: String, code: String, name: String) {
        prefs.edit().putString("db", db.trim()).putString("code", code).putString("name", name.trim()).apply()
        Cloud.baseUrl = db.trim()
        controlLoaded = false
    }

    fun seenRequests(): Set<String> = prefs.getStringSet("seen", emptySet()) ?: emptySet()
    fun markSeen(ids: Set<String>) { prefs.edit().putStringSet("seen", ids).apply() }

    suspend fun fetchRequests(): List<FileRequest>? {
        val pd = Cloud.getObj(path("pending")) ?: return if (Cloud.reachable(path("pending"))) emptyList() else null
        val out = mutableListOf<FileRequest>()
        for (k in pd.keys().asSequence().toList()) {
            val o = pd.optJSONObject(k) ?: continue
            out.add(FileRequest(k, o.optString("direction"), o.optString("peer"), o.optString("fileName"), o.optLong("size"), o.optLong("time")))
        }
        return out.sortedBy { it.time }
    }

    suspend fun refresh() {
        if (!configured()) return
        status = Cloud.getObj(path("status"))
        val r = fetchRequests()
        reachable = r != null
        if (r != null) requests = r
        val lg = Cloud.getObj(path("log"), "orderBy=%22%24key%22&limitToLast=100")
        if (lg != null) {
            val lines = mutableListOf<LogLine>()
            for (k in lg.keys().asSequence().toList()) {
                val o = lg.optJSONObject(k) ?: continue
                lines.add(LogLine(o.optLong("t"), o.optString("m")))
            }
            log = lines.sortedByDescending { it.time }
        }
        if (!controlLoaded) {
            val c = Cloud.getObj(path("control"))
            if (c != null) control = Control.fromJson(c)
            if (reachable == true) controlLoaded = true
        }
    }

    fun decide(id: String, decision: String) {
        requests = requests.filter { it.id != id }
        scope.launch {
            Cloud.put(path("decisions/$id"), JSONObject().put("d", decision))
            Cloud.delete(path("pending/$id"))
        }
    }

    /** Saves rule changes. lockNow and locateNowTs are one-shot commands and are never saved from here. */
    fun update(change: (Control) -> Control) {
        control = change(control)
        val j = control.toJson()
        j.remove("lockNow"); j.remove("locateNowTs")
        scope.launch { Cloud.patch(path("control"), j) }
    }

    fun lockNow() { scope.launch { Cloud.patch(path("control"), JSONObject().put("lockNow", true)) } }
    fun locateNow() { scope.launch { Cloud.patch(path("control"), JSONObject().put("locateNowTs", System.currentTimeMillis())) } }
}
