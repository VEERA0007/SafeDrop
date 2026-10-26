package com.safedrop.kid

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.safedrop.core.Cloud
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val nearby: NearbyManager get() = (application as KidApp).nearby

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { KidScreen(nearby, nearbyPermissions()) } }
    }

    override fun onResume() {
        super.onResume()
        if (KidState.configured()) startMonitor(this)
    }

    private fun nearbyPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

fun startMonitor(ctx: Context) {
    ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
}

@Composable
fun KidScreen(nearby: NearbyManager, perms: Array<String>) {
    var tab by remember { mutableStateOf(if (KidState.configured()) 0 else 1) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("📡") }, label = { Text("Share") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("⚙️") }, label = { Text("Setup") })
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            if (tab == 0) ShareScreen(nearby, perms) else SetupScreen()
        }
    }
    val prompt = nearby.authPrompt
    if (prompt != null) {
        AlertDialog(
            onDismissRequest = { nearby.denyConnection(prompt) },
            title = { Text("Connect to ${prompt.name}?") },
            text = { Text("Check that this code matches on the other phone:\n\n${prompt.digits}") },
            confirmButton = { TextButton({ nearby.confirmConnection(prompt) }) { Text("Connect") } },
            dismissButton = { TextButton({ nearby.denyConnection(prompt) }) { Text("Decline") } },
        )
    }
}

@Composable
fun ShareScreen(nearby: NearbyManager, perms: Array<String>) {
    var target by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) nearby.start() else nearby.status = "Permissions are required to find nearby devices"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = target
        if (uri != null && t != null) nearby.queueSend(t, uri)
    }

    Text("SafeDrop", style = MaterialTheme.typography.headlineMedium)
    if (!KidState.configured()) {
        Text("Ask your parent to pair this phone first (Setup tab).")
        return
    }
    Text(nearby.status, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Button({ permLauncher.launch(perms) }) { Text("Find nearby") }
        OutlinedButton({ nearby.stop() }) { Text("Stop") }
    }
    Text("Files you send or receive wait for your parent's approval.", style = MaterialTheme.typography.labelMedium)

    if (Store.pending.isNotEmpty()) {
        Text("Waiting for parent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Store.pending.forEach { Text("${if (it.direction == "IN") "From" else "To"} ${it.peer}: ${it.fileName}") }
    }

    Text("Connected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (nearby.connected.isEmpty()) Text("No one yet")
    nearby.connected.forEach { (id, name) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name)
            Button({ target = id; picker.launch(arrayOf("*/*")) }) { Text("Send file") }
        }
    }

    Text("Nearby devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (nearby.discovered.isEmpty()) Text("Nothing found yet")
    nearby.discovered.filterKeys { it !in nearby.connected }.forEach { (id, name) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name)
            OutlinedButton({ nearby.connect(id) }) { Text("Connect") }
        }
    }
}

@Composable
fun SetupScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(KidState.configured()) }

    Text("Setup", style = MaterialTheme.typography.headlineMedium)

    if (!configured) {
        var db by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        Text("A parent should do this step, using the details from the SafeDrop Parent app.")
        OutlinedTextField(db, { db = it }, label = { Text("Database address (https://...)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("This child's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("Parent PIN (needed to unpair)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
        )
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, onClick = {
            if (!db.startsWith("https://") || code.length < 8 || name.isBlank() || pin.length < 4) {
                msg = "Fill in every box (PIN: at least 4 digits)."
            } else {
                busy = true; msg = "Checking..."
                scope.launch {
                    val old = Cloud.baseUrl
                    Cloud.baseUrl = db.trim()
                    val ok = Cloud.reachable("pairs/${code.trim()}")
                    if (ok) {
                        KidState.saveConfig(db, code, name)
                        KidState.setPin(pin)
                        Store.addLog("Phone paired: ${name.trim()} (${Build.MODEL})")
                        startMonitor(ctx)
                        configured = true
                    } else {
                        Cloud.baseUrl = old
                        msg = "Could not reach the database. Check the address and internet."
                    }
                    busy = false
                }
            }
        }) { Text("Connect to parent") }
    } else {
        ConnectedSetup(onUnpaired = { configured = false })
    }
}

@Composable
fun ConnectedSetup(onUnpaired: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var askPin by remember { mutableStateOf(false) }
    var pinText by remember { mutableStateOf("") }
    var pinErr by remember { mutableStateOf("") }
    val dummy = refresh // re-read statuses after returning from system screens

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        startMonitor(ctx)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    Text("Paired as ${KidState.childName}.", style = MaterialTheme.typography.titleMedium)
    Text("Turn these on so your parent can keep you safe:", modifier = Modifier.padding(top = 8.dp))

    val locOk = LocationTracker.hasPermission(ctx)
    val usageOk = Telemetry.hasUsageAccess(ctx)
    val adminOk = Enforcer.adminActive(ctx)

    StatusRow("Location", locOk) {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(list.toTypedArray())
    }
    if (locOk) {
        StatusRow("Location when the app is closed", ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == 0) {
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    StatusRow("App usage and screen time", usageOk) {
        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        refresh++
    }
    StatusRow("Protect from uninstall / allow locking", adminOk) {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, AdminReceiver::class.java))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets your parent lock this phone and stops SafeDrop being removed without them knowing.")
        adminLauncher.launch(i)
    }
    OutlinedButton({ refresh++ }, modifier = Modifier.padding(top = 4.dp)) { Text("Refresh status") }

    Text("What your parent can see", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        "• Where this phone is\n• How long you use each app today, and which apps are installed\n" +
            "• Battery level and when the phone last checked in\n• Files you send or receive with SafeDrop\n" +
            "• When the phone is locked by a limit or by your parent\n\n" +
            "Your parent cannot read your messages, photos, passwords or what you type. " +
            "A notification stays visible while this is on.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedButton({ askPin = true; pinText = ""; pinErr = "" }, modifier = Modifier.padding(top = 16.dp)) { Text("Unpair (parent PIN)") }

    if (askPin) {
        AlertDialog(
            onDismissRequest = { askPin = false },
            title = { Text("Parent PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        pinText, { pinText = it.filter(Char::isDigit).take(8) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (pinErr.isNotEmpty()) Text(pinErr, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton({
                    if (KidState.checkPin(pinText)) {
                        Store.addLog("Phone was unpaired with the parent PIN")
                        ctx.stopService(Intent(ctx, MonitorService::class.java))
                        KidState.clearConfig()
                        askPin = false
                        onUnpaired()
                    } else pinErr = "Wrong PIN"
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton({ askPin = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text((if (ok) "✅ " else "⬜ ") + label, modifier = Modifier.weight(1f))
        if (!ok) OutlinedButton(onFix) { Text("Turn on") }
    }
}
