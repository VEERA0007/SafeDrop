package com.safedrop.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.util.UUID

data class AuthPrompt(val endpointId: String, val name: String, val digits: String)

/**
 * Nearby Connections wrapper (Wi-Fi/Bluetooth, offline, like AirDrop) with parental gates:
 *  - peer check at connection time (trusted-only, quiet hours)
 *  - file check on metadata and again on completion (type, size)
 *  - incoming files are quarantined; outgoing files are held until a parent approves.
 */
class NearbyManager(context: Context) {
    private val ctx = context.applicationContext
    private val client = Nearby.getConnectionsClient(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val myName = "${Build.MODEL}-${UUID.randomUUID().toString().take(3)}"

    val discovered = mutableStateMapOf<String, String>()
    val connected = mutableStateMapOf<String, String>()
    var status by mutableStateOf("Not started")
    var authPrompt by mutableStateOf<AuthPrompt?>(null)

    private val names = mutableMapOf<String, String>()
    private val meta = mutableMapOf<Long, Pair<String, Long>>()      // payloadId -> (name, size)
    private val incoming = mutableMapOf<Long, Payload>()             // payloadId -> file payload
    private val refused = mutableSetOf<Long>()
    private val owner = mutableMapOf<Long, String>()                 // payloadId -> endpointId
    private val outgoing = mutableMapOf<Long, Pending>()             // file payloadId -> pending item

    private val pol get() = Store.policy.value

    // ---------------------------------------------------------------- discovery
    fun start() {
        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val dis = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(myName, SERVICE_ID, lifecycle, adv)
            .addOnFailureListener { status = "Advertising failed: ${it.message}" }
        client.startDiscovery(SERVICE_ID, discoveryCb, dis)
            .addOnSuccessListener { status = "Looking for nearby devices as \"$myName\"" }
            .addOnFailureListener { status = "Discovery failed: ${it.message}" }
    }

    fun stop() {
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        discovered.clear(); connected.clear(); status = "Stopped"
    }

    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            discovered[id] = info.endpointName
        }
        override fun onEndpointLost(id: String) { discovered.remove(id) }
    }

    fun connect(endpointId: String) {
        val name = discovered[endpointId] ?: return
        val blockReason: String? = ParentRules.checkPeer(name, pol)
        if (blockReason != null) {
            Store.addLog("Blocked connecting to $name: $blockReason"); status = "Blocked: $blockReason"; return
        }
        client.requestConnection(myName, endpointId, lifecycle)
            .addOnFailureListener { status = "Connect failed: ${it.message}" }
    }

    // ---------------------------------------------------------------- connection
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            names[id] = info.endpointName
            val reason = ParentRules.checkPeer(info.endpointName, pol)
            if (reason != null) {
                client.rejectConnection(id)
                Store.addLog("Refused connection from ${info.endpointName}: $reason")
                return
            }
            authPrompt = AuthPrompt(id, info.endpointName, info.authenticationDigits)
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connected[id] = names[id] ?: id
                Store.addLog("Connected to ${names[id] ?: id}")
            } else {
                Store.addLog("Connection with ${names[id] ?: id} not established")
            }
        }

        override fun onDisconnected(id: String) { connected.remove(id) }
    }

    fun confirmConnection(p: AuthPrompt) {
        client.acceptConnection(p.endpointId, payloadCb); authPrompt = null
    }

    fun denyConnection(p: AuthPrompt) {
        client.rejectConnection(p.endpointId); authPrompt = null
    }

    // ---------------------------------------------------------------- receiving
    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            owner[payload.id] = endpointId
            when (payload.type) {
                Payload.Type.BYTES -> {
                    // metadata: "<filePayloadId>|<size>|<name>"
                    val parts = String(payload.asBytes() ?: return).split("|", limit = 3)
                    if (parts.size < 3) return
                    val fid = parts[0].toLongOrNull() ?: return
                    val size = parts[1].toLongOrNull() ?: 0L
                    val name = sanitize(parts[2])
                    meta[fid] = name to size
                    val peer = names[endpointId] ?: endpointId
                    val bad: String? = ParentRules.checkFile(name, size, pol)
                    if (bad != null) {
                        refused.add(fid); client.cancelPayload(fid)
                        Store.addLog("Rejected incoming \"$name\" from $peer: $bad")
                    }
                }
                Payload.Type.FILE -> {
                    if (payload.id in refused) client.cancelPayload(payload.id)
                    else incoming[payload.id] = payload
                }
                else -> client.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            if (u.status != PayloadTransferUpdate.Status.SUCCESS) return
            val id = u.payloadId
            outgoing.remove(id)?.let {
                File(it.path).delete()
                Store.addLog("Sent \"${it.fileName}\" to ${it.peer}")
                return
            }
            val payload = incoming.remove(id) ?: return
            val (name, size) = meta.remove(id) ?: ("file_$id" to u.totalBytes)
            val peer = names[endpointId] ?: endpointId
            val reason = ParentRules.checkFile(name, size, pol)
            Thread {
                val dir = File(ctx.filesDir, "quarantine").apply { mkdirs() }
                val dest = File(dir, "${id}_$name")
                runCatching {
                    payload.asFile()!!.asParcelFileDescriptor().use { pfd ->
                        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
                            dest.outputStream().use { ins.copyTo(it) }
                        }
                    }
                    payload.asFile()?.asJavaFile()?.delete()
                }
                main.post {
                    if (reason != null || !dest.exists()) {
                        dest.delete()
                        Store.addLog("Rejected incoming \"$name\" from $peer: ${reason ?: "copy failed"}")
                    } else if (pol.enabled && pol.approveIncoming) {
                        Store.addPending(Pending(UUID.randomUUID().toString(), "IN", peer, endpointId, name, dest.length(), dest.path, System.currentTimeMillis()))
                        Store.addLog("Received \"$name\" from $peer - waiting for parent approval")
                    } else {
                        deliver(dest, name, peer)
                    }
                }
            }.start()
        }
    }

    private fun deliver(file: File, name: String, peer: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/SafeDrop")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { Store.addLog("Could not save \"$name\""); return }
        ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
        Store.addLog("Saved \"$name\" from $peer to Downloads/SafeDrop")
    }

    // ---------------------------------------------------------------- sending
    /** Called when the child picks a file for a connected device. */
    fun queueSend(endpointId: String, uri: Uri) {
        val peer = connected[endpointId] ?: return
        var name = "file"; var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = sanitize(c.getString(it)) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val fileReason: String? = ParentRules.checkFile(name, size, pol)
        if (fileReason != null) {
            Store.addLog("Blocked sending \"$name\" to $peer: $fileReason"); status = "Not sent: $fileReason"; return
        }
        if (ParentRules.inQuietHours(pol)) {
            Store.addLog("Blocked sending \"$name\": quiet hours"); status = "Not sent: quiet hours"; return
        }
        Thread {
            val dir = File(ctx.filesDir, "outbox").apply { mkdirs() }
            val copy = File(dir, "${UUID.randomUUID()}_$name")
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { i.copyTo(it) } }
            }
            main.post {
                if (!copy.exists()) { status = "Could not read file"; return@post }
                val p = Pending(UUID.randomUUID().toString(), "OUT", peer, endpointId, name, copy.length(), copy.path, System.currentTimeMillis())
                if (pol.enabled && pol.approveOutgoing) {
                    Store.addPending(p)
                    Store.addLog("Wants to send \"$name\" to $peer - waiting for parent approval")
                    status = "Waiting for parent approval"
                } else sendNow(p)
            }
        }.start()
    }

    private fun sendNow(p: Pending) {
        if (p.endpointId !in connected) {
            Store.addLog("Cannot send \"${p.fileName}\": ${p.peer} disconnected")
            File(p.path).delete(); return
        }
        val pfd = ParcelFileDescriptor.open(File(p.path), ParcelFileDescriptor.MODE_READ_ONLY)
        val filePayload = Payload.fromFile(pfd)
        outgoing[filePayload.id] = p
        val metaPayload = Payload.fromBytes("${filePayload.id}|${p.size}|${p.fileName}".toByteArray())
        client.sendPayload(p.endpointId, metaPayload)
        client.sendPayload(p.endpointId, filePayload)
        status = "Sending \"${p.fileName}\"..."
    }

    // ---------------------------------------------------------------- parent decisions
    fun approve(p: Pending) {
        Store.removePending(p)
        Store.addLog("Parent approved ${if (p.direction == "IN") "incoming" else "outgoing"} \"${p.fileName}\"")
        if (p.direction == "IN") { deliver(File(p.path), p.fileName, p.peer) } else sendNow(p)
    }

    fun reject(p: Pending) {
        Store.removePending(p)
        File(p.path).delete()
        Store.addLog("Parent rejected ${if (p.direction == "IN") "incoming" else "outgoing"} \"${p.fileName}\"")
    }

    private fun sanitize(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "file" }

    companion object { const val SERVICE_ID = "com.safedrop.app.SERVICE" }
}
