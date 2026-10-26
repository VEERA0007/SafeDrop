package com.safedrop.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

fun JSONArray?.toStringSet(): Set<String> =
    if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

fun Collection<String>.toJsonArray(): JSONArray = JSONArray(this.toList())

fun newPairingCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val r = SecureRandom()
    return (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
}

/** Rules for AirDrop-style file sharing, edited from the parent app. */
data class FilePolicy(
    val enabled: Boolean = true,
    val approveIncoming: Boolean = true,
    val approveOutgoing: Boolean = true,
    val blockedExt: Set<String> = setOf("apk", "xapk", "apks", "exe", "bat", "sh", "jar", "msi"),
    val maxSizeMb: Int = 50,
    val trustedOnly: Boolean = false,
    val trusted: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("approveIncoming", approveIncoming)
        put("approveOutgoing", approveOutgoing)
        put("blockedExt", blockedExt.toJsonArray())
        put("maxSizeMb", maxSizeMb)
        put("trustedOnly", trustedOnly)
        put("trusted", trusted.toJsonArray())
    }

    companion object {
        fun fromJson(o: JSONObject?): FilePolicy {
            val d = FilePolicy()
            if (o == null) return d
            return FilePolicy(
                enabled = o.optBoolean("enabled", d.enabled),
                approveIncoming = o.optBoolean("approveIncoming", d.approveIncoming),
                approveOutgoing = o.optBoolean("approveOutgoing", d.approveOutgoing),
                blockedExt = if (o.has("blockedExt")) o.optJSONArray("blockedExt").toStringSet() else d.blockedExt,
                maxSizeMb = o.optInt("maxSizeMb", d.maxSizeMb),
                trustedOnly = o.optBoolean("trustedOnly", d.trustedOnly),
                trusted = o.optJSONArray("trusted").toStringSet(),
            )
        }
    }
}

/** Everything a parent can control remotely. Stored at pairs/<code>/control. */
data class Control(
    val lockNow: Boolean = false,
    val dailyLimitMin: Int = 0,          // 0 = no limit
    val bedtimeOn: Boolean = false,
    val bedStartHour: Int = 21,
    val bedEndHour: Int = 7,
    val blockedApps: Set<String> = emptySet(),
    val blockedDomains: Set<String> = emptySet(),
    val locateNowTs: Long = 0L,
    val file: FilePolicy = FilePolicy(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lockNow", lockNow)
        put("dailyLimitMin", dailyLimitMin)
        put("bedtimeOn", bedtimeOn)
        put("bedStartHour", bedStartHour)
        put("bedEndHour", bedEndHour)
        put("blockedApps", blockedApps.toJsonArray())
        put("blockedDomains", blockedDomains.toJsonArray())
        put("locateNowTs", locateNowTs)
        put("file", file.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject?): Control {
            if (o == null) return Control()
            val d = Control()
            return Control(
                lockNow = o.optBoolean("lockNow", d.lockNow),
                dailyLimitMin = o.optInt("dailyLimitMin", d.dailyLimitMin),
                bedtimeOn = o.optBoolean("bedtimeOn", d.bedtimeOn),
                bedStartHour = o.optInt("bedStartHour", d.bedStartHour),
                bedEndHour = o.optInt("bedEndHour", d.bedEndHour),
                blockedApps = o.optJSONArray("blockedApps").toStringSet(),
                blockedDomains = o.optJSONArray("blockedDomains").toStringSet(),
                locateNowTs = o.optLong("locateNowTs", 0L),
                file = FilePolicy.fromJson(o.optJSONObject("file")),
            )
        }
    }
}
