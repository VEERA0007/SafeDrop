package com.safedrop.kid

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Calendar

/** Read-only device facts the parent is told about. Everything here is disclosed on the Setup screen. */
object Telemetry {
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Battery percent and whether it is charging. */
    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return (if (level < 0) -1 else level * 100 / scale) to charging
    }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** Apps with a launcher icon: list of (name, package). */
    fun installedApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    /** Today's foreground time per app: list of (package, label, minutes), biggest first. */
    fun usageToday(ctx: Context): List<Triple<String, String, Int>> {
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
        return stats.groupBy { it.packageName }
            .map { (pkg, list) -> pkg to (list.sumOf { it.totalTimeInForeground } / 60000L).toInt() }
            .filter { it.second >= 1 }
            .sortedByDescending { it.second }
            .map { Triple(it.first, label(ctx, it.first), it.second) }
    }

    /** The app currently on screen, judged from the last 10 minutes of usage events. */
    fun foregroundPackage(ctx: Context): String? {
        if (!hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 10 * 60_000L, end)
        val e = UsageEvents.Event()
        var cur: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) cur = e.packageName
            else if (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED && e.packageName == cur) cur = null
        }
        return cur
    }
}

object LocationTracker {
    @Volatile var last: Location? = null
    private var started = false

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (started || !hasPermission(ctx)) return
        started = true
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000L)
            .setMinUpdateIntervalMillis(60_000L).build()
        client.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { last = it } }
        }, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { if (it != null && last == null) last = it }
    }

    /** One fresh, accurate fix (used when the parent taps "Locate now"). */
    @SuppressLint("MissingPermission")
    fun refreshNow(ctx: Context, done: () -> Unit) {
        if (!hasPermission(ctx)) { done(); return }
        LocationServices.getFusedLocationProviderClient(ctx)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { if (it != null) last = it; done() }
            .addOnFailureListener { done() }
    }
}

object Enforcer {
    private fun comp(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun adminActive(ctx: Context): Boolean =
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(comp(ctx))

    fun lock(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(comp(ctx))) return false
        dpm.lockNow()
        return true
    }
}
