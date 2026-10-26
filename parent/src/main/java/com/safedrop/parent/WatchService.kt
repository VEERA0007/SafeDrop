package com.safedrop.parent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.safedrop.core.Cloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Optional "instant alerts": checks every 10 s for new file requests and for a phone that stopped checking in. */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var offlineAlerted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "Watching", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("alerts", "Alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "watch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SafeDrop Parent")
            .setContentText("Watching for ${Repo.kidName}'s requests")
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); job = null; super.onDestroy() }

    private suspend fun loop() {
        while (scope.isActive) {
            try { check() } catch (e: Exception) { }
            delay(10_000)
        }
    }

    private suspend fun check() {
        if (!Repo.configured()) return
        val reqs = Repo.fetchRequests() ?: return
        val seen = Repo.seenRequests().toMutableSet()
        for (r in reqs) {
            if (seen.add(r.id)) {
                val what = if (r.direction == "IN") "wants to receive" else "wants to send"
                alert(r.id.hashCode(), "${Repo.kidName} $what a file", "${r.fileName} (${r.size / 1024} KB) with ${r.peer}")
            }
        }
        Repo.markSeen(seen)
        val t = Cloud.getObj(Repo.path("status"))?.optLong("t") ?: 0L
        val stale = t > 0 && System.currentTimeMillis() - t > 30 * 60_000L
        if (stale && !offlineAlerted) { offlineAlerted = true; alert(1001, "${Repo.kidName}'s phone is offline", "No check-in for over 30 minutes.") }
        if (!stale) offlineAlerted = false
    }

    private fun alert(id: Int, title: String, text: String) {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
