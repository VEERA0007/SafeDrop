package com.safedrop.kid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.safedrop.core.Cloud
import com.safedrop.core.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Foreground service with a permanent, visible notification. Every 15 s it:
 * reads the parent's controls, applies approve/reject decisions, enforces limits, and every minute reports status.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastStatus = 0L
    private var lastApps = 0L
    private var lastLocateTs = 0L
    private val lastAction = mutableMapOf<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotice()
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        job = null
        super.onDestroy()
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("monitor", "Parent monitoring notice", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, "monitor")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SafeDrop Kids is active")
            .setContentText("Your parent can see this phone's location, app use and file sharing.")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (LocationTracker.hasPermission(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, 1, n, type)
        } catch (e: Exception) {
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    private suspend fun loop() {
        LocationTracker.start(this)
        while (scope.isActive) {
            try { tick() } catch (e: Exception) { /* try again next round */ }
            delay(15_000)
        }
    }

    private suspend fun tick() {
        if (!KidState.configured()) return
        LocationTracker.start(this)
        val now = System.currentTimeMillis()

        // 1. Controls set by the parent
        val c = Cloud.getObj(KidState.path("control"))
        if (c != null) {
            val ctl = Control.fromJson(c)
            KidState.updateControl(ctl)
            if (ctl.lockNow) {
                if (Enforcer.lock(this)) Store.addLog("Phone locked by parent")
                Cloud.patch(KidState.path("control"), JSONObject().put("lockNow", false))
            }
            if (ctl.locateNowTs > lastLocateTs) {
                lastLocateTs = ctl.locateNowTs
                LocationTracker.refreshNow(this) { scope.launch { pushStatus() } }
            }
        }

        // 2. Parent decisions on file requests
        val decisions = Cloud.getObj(KidState.path("decisions"))
        if (decisions != null) {
            val ids = decisions.keys().asSequence().toList()
            for (id in ids) {
                val dec = decisions.optJSONObject(id)?.optString("d") ?: ""
                val p = Store.pending.firstOrNull { it.id == id }
                if (p != null && (dec == "approve" || dec == "reject")) {
                    withContext(Dispatchers.Main) {
                        val nearby = (application as KidApp).nearby
                        if (dec == "approve") nearby.approve(p) else nearby.reject(p)
                    }
                }
                Cloud.delete(KidState.path("decisions/$id"))
            }
        }

        // 3. Limits
        enforce(now)

        // 4. Report status
        if (now - lastStatus >= 60_000L) pushStatus()
    }

    private fun inBedtime(c: Control): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (c.bedStartHour <= c.bedEndHour) h in c.bedStartHour until c.bedEndHour
        else h >= c.bedStartHour || h < c.bedEndHour
    }

    private fun enforce(now: Long) {
        val ctl = KidState.control
        if (!Telemetry.hasUsageAccess(this)) return
        val fg = Telemetry.foregroundPackage(this) ?: return
        if (fg == packageName || fg == "com.android.systemui") return
        val callApp = fg.contains("dialer") || fg.contains("incallui") || fg.contains("telecom")
        var reason: String? = null
        if (fg in ctl.blockedApps) {
            reason = "blocked app " + Telemetry.label(this, fg)
        } else if (!callApp) {
            if (ctl.bedtimeOn && inBedtime(ctl)) {
                reason = "bedtime"
            } else if (ctl.dailyLimitMin > 0) {
                val used = Telemetry.usageToday(this).filter { it.first != packageName }.sumOf { it.third }
                if (used >= ctl.dailyLimitMin) reason = "daily screen-time limit reached"
            }
        }
        if (reason != null && now - (lastAction[reason] ?: 0L) > 30_000L) {
            lastAction[reason] = now
            if (Enforcer.lock(this)) Store.addLog("Phone locked: $reason")
        }
    }

    private suspend fun pushStatus() {
        val now = System.currentTimeMillis()
        lastStatus = now
        val (bat, charging) = Telemetry.battery(this)
        val o = JSONObject()
            .put("t", now)
            .put("name", KidState.childName)
            .put("model", Build.MODEL)
            .put("battery", bat)
            .put("charging", charging)
            .put("usageAccess", Telemetry.hasUsageAccess(this))
            .put("adminActive", Enforcer.adminActive(this))
            .put("locPerm", LocationTracker.hasPermission(this))
        LocationTracker.last?.let {
            o.put("lat", it.latitude).put("lng", it.longitude).put("acc", it.accuracy.toInt()).put("locT", it.time)
        }
        val usage = Telemetry.usageToday(this).filter { it.first != packageName }
        o.put("usageTotalMin", usage.sumOf { it.third })
        o.put("usage", JSONArray().apply {
            usage.take(25).forEach { put(JSONObject().put("app", it.second).put("pkg", it.first).put("min", it.third)) }
        })
        if (now - lastApps > 10 * 60_000L) {
            lastApps = now
            o.put("apps", JSONArray().apply {
                Telemetry.installedApps(this@MonitorService).forEach { put(JSONObject().put("n", it.first).put("p", it.second)) }
            })
        }
        Cloud.patch(KidState.path("status"), o)
    }
}
