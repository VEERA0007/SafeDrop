package com.safedrop.app

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** What happens to a file when a rule direction (send / receive) is evaluated. */
enum class Mode(val label: String) {
    ALLOW("Allow"),
    ASK_PARENT("Ask a parent (PIN)"),
    BLOCK("Block everything")
}

/** File-type groups a parent can switch off. Matching is by file extension. */
enum class Category(val label: String, val extensions: Set<String>) {
    APPS(
        "Apps & installers",
        setOf("apk", "xapk", "apks", "aab", "exe", "msi", "bat", "cmd", "sh", "jar", "dex", "scr", "com")
    ),
    VIDEOS("Videos", setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v")),
    IMAGES("Images", setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")),
    AUDIO("Audio", setOf("mp3", "wav", "aac", "m4a", "ogg", "flac", "opus")),
    DOCUMENTS("Documents", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub")),
    ARCHIVES("Archives", setOf("zip", "rar", "7z", "tar", "gz"))
}

data class LogEntry(
    val time: Long,
    val direction: String, // "IN" or "OUT"
    val fileName: String,
    val size: Long,
    val peer: String,
    val outcome: String
)

/** All parental settings, the PIN and the activity log. Stored privately on this device. */
class ParentalStore(context: Context) {

    private val p = context.getSharedPreferences("safedrop_parental", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- PIN

    val hasPin: Boolean get() = p.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", hash(pin, salt))
            .putInt("pin_fails", 0)
            .putLong("pin_lock_until", 0L)
            .apply()
    }

    /** Milliseconds left on the wrong-PIN lockout (0 when not locked). */
    fun lockRemainingMs(): Long =
        (p.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Checks the PIN. Five wrong tries in a row lock PIN entry for one minute. */
    fun tryPin(pin: String): Boolean {
        if (lockRemainingMs() > 0) return false
        val saltStr = p.getString("pin_salt", null) ?: return false
        val stored = p.getString("pin_hash", null) ?: return false
        val candidate = hash(pin, Base64.decode(saltStr, Base64.NO_WRAP))
        if (MessageDigest.isEqual(candidate.toByteArray(), stored.toByteArray())) {
            p.edit().putInt("pin_fails", 0).apply()
            return true
        }
        val fails = p.getInt("pin_fails", 0) + 1
        val e = p.edit()
        if (fails >= 5) {
            e.putInt("pin_fails", 0).putLong("pin_lock_until", System.currentTimeMillis() + 60_000L)
        } else {
            e.putInt("pin_fails", fails)
        }
        e.apply()
        return false
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 50_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- Rules

    var deviceName: String
        get() = p.getString("device_name", null)
            ?: ("SafeDrop-" + (Build.MODEL ?: "device").take(10))
        set(v) = p.edit().putString("device_name", v.trim().take(20)).apply()

    var receiveMode: Mode
        get() = modeOf("receive_mode", Mode.ASK_PARENT)
        set(v) = p.edit().putString("receive_mode", v.name).apply()

    var sendMode: Mode
        get() = modeOf("send_mode", Mode.ALLOW)
        set(v) = p.edit().putString("send_mode", v.name).apply()

    var blockedCategories: Set<Category>
        get() = (p.getStringSet("blocked_cats", null) ?: setOf(Category.APPS.name))
            .mapNotNull { runCatching { Category.valueOf(it) }.getOrNull() }
            .toSet()
        set(v) = p.edit().putStringSet("blocked_cats", v.map { it.name }.toSet()).apply()

    var maxSizeMb: Int
        get() = p.getInt("max_mb", 50)
        set(v) = p.edit().putInt("max_mb", v.coerceIn(1, 500)).apply()

    /** Empty = any nearby device may connect. Otherwise only these device names. */
    var allowedPeers: Set<String>
        get() = (p.getStringSet("allowed_peers", null) ?: emptySet()).toSet()
        set(v) = p.edit().putStringSet("allowed_peers", v.toSet()).apply()

    var quietEnabled: Boolean
        get() = p.getBoolean("quiet_on", false)
        set(v) = p.edit().putBoolean("quiet_on", v).apply()

    var quietStartHour: Int
        get() = p.getInt("quiet_start", 21)
        set(v) = p.edit().putInt("quiet_start", v).apply()

    var quietEndHour: Int
        get() = p.getInt("quiet_end", 7)
        set(v) = p.edit().putInt("quiet_end", v).apply()

    private fun modeOf(key: String, default: Mode): Mode =
        runCatching { Mode.valueOf(p.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    // ---------------------------------------------------------------- Activity log

    fun log(): List<LogEntry> {
        val raw = p.getString("log", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LogEntry(
                    o.getLong("t"), o.getString("d"), o.getString("n"),
                    o.getLong("s"), o.getString("p"), o.getString("o")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addLog(e: LogEntry) {
        val list = (listOf(e) + log()).take(200)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("t", it.time).put("d", it.direction).put("n", it.fileName)
                    .put("s", it.size).put("p", it.peer).put("o", it.outcome)
            )
        }
        p.edit().putString("log", arr.toString()).apply()
    }

    fun clearLog() {
        p.edit().remove("log").apply()
    }
}
