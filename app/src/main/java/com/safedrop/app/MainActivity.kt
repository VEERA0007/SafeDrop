package com.safedrop.app

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

class MainActivity : ComponentActivity() {
    private lateinit var nearby: NearbyManager

    private fun neededPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        nearby = NearbyManager(this)
        setContent { MaterialTheme { App(nearby, neededPermissions()) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) nearby.stop()
    }
}

@Composable
fun App(nearby: NearbyManager, perms: Array<String>) {
    var tab by remember { mutableStateOf(0) }
    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab != 1) unlocked = false }

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("📡") }, label = { Text("Share") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("🔒") },
                label = { Text("Parent" + if (Store.pending.isNotEmpty()) " (${Store.pending.size})" else "") })
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (tab == 0) ShareScreen(nearby, perms)
            else if (!unlocked) PinGate { unlocked = true }
            else ParentScreen(nearby)
        }
    }

    nearby.authPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { nearby.denyConnection(p) },
            title = { Text("Connect to ${p.name}?") },
            text = { Text("Check that this code matches on the other phone:\n\n${p.digits}") },
            confirmButton = { TextButton({ nearby.confirmConnection(p) }) { Text("Connect") } },
            dismissButton = { TextButton({ nearby.denyConnection(p) }) { Text("Decline") } },
        )
    }
}

// ------------------------------------------------------------------ child side
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
    Text(nearby.status, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Button({ permLauncher.launch(perms) }) { Text("Find nearby") }
        OutlinedButton({ nearby.stop() }) { Text("Stop") }
    }
    if (Store.policy.value.enabled) {
        Text("Parental controls are ON. Files need a parent's approval.", style = MaterialTheme.typography.labelMedium)
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

// ------------------------------------------------------------------ parent side
@Composable
fun PinGate(onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val creating = !Store.hasPin()

    Text(if (creating) "Create a parent PIN" else "Enter parent PIN", style = MaterialTheme.typography.headlineSmall)
    if (creating) Text("Only a parent should know this PIN. It protects approvals and rules.")
    OutlinedTextField(
        value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) },
        label = { Text("PIN (4-8 digits)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    Button({
        if (pin.length < 4) error = "PIN must be at least 4 digits"
        else if (creating) { Store.setPin(pin); onUnlocked() }
        else if (Store.checkPin(pin)) onUnlocked()
        else { error = "Wrong PIN"; pin = "" }
    }) { Text(if (creating) "Save PIN" else "Unlock") }
}

@Composable
fun ParentScreen(nearby: NearbyManager) {
    val p = Store.policy.value
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Approvals", style = MaterialTheme.typography.headlineSmall) }
        if (Store.pending.isEmpty()) item { Text("Nothing waiting.") }
        items(Store.pending.toList(), key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (item.direction == "IN") "Incoming from ${item.peer}" else "Child wants to send to ${item.peer}",
                        style = MaterialTheme.typography.labelLarge)
                    Text("${item.fileName}  (${item.size / 1024} KB)")
                    Text(Store.fmt(item.time), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button({ nearby.approve(item) }) { Text("Approve") }
                        OutlinedButton({ nearby.reject(item) }) { Text("Reject") }
                    }
                }
            }
        }

        item { Text("Rules", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
        item { SwitchRow("Parental controls on", p.enabled) { Store.setPolicy(p.copy(enabled = it)) } }
        item { SwitchRow("Approve incoming files", p.approveIncoming) { Store.setPolicy(p.copy(approveIncoming = it)) } }
        item { SwitchRow("Approve outgoing files", p.approveOutgoing) { Store.setPolicy(p.copy(approveOutgoing = it)) } }
        item { SwitchRow("Trusted devices only", p.trustedOnly) { Store.setPolicy(p.copy(trustedOnly = it)) } }
        item {
            Text("Max file size: ${p.maxSizeMb} MB")
            Slider(value = p.maxSizeMb.toFloat(), onValueChange = { Store.setPolicy(p.copy(maxSizeMb = it.toInt().coerceAtLeast(1))) },
                valueRange = 1f..500f)
        }
        item {
            var text by remember(p.blockedExt) { mutableStateOf(p.blockedExt.sorted().joinToString(", ")) }
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Blocked file types (comma separated)") },
                modifier = Modifier.fillMaxWidth())
            TextButton({
                Store.setPolicy(p.copy(blockedExt = text.split(",").map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()))
            }) { Text("Save file types") }
        }
        item { SwitchRow("Quiet hours (no sharing)", p.quietEnabled) { Store.setPolicy(p.copy(quietEnabled = it)) } }
        if (p.quietEnabled) {
            item {
                Text("From ${p.quietStartHour}:00 to ${p.quietEndHour}:00")
                Slider(value = p.quietStartHour.toFloat(), onValueChange = { Store.setPolicy(p.copy(quietStartHour = it.toInt())) }, valueRange = 0f..23f)
                Slider(value = p.quietEndHour.toFloat(), onValueChange = { Store.setPolicy(p.copy(quietEndHour = it.toInt())) }, valueRange = 0f..23f)
            }
        }

        item { Text("Trusted devices", style = MaterialTheme.typography.titleMedium) }
        item {
            Text("Add a device from the lists below (shown while nearby or connected).", style = MaterialTheme.typography.labelSmall)
            (nearby.discovered.values + nearby.connected.values).toSet().filter { it !in p.trusted }.forEach { name ->
                TextButton({ Store.setPolicy(p.copy(trusted = p.trusted + name)) }) { Text("+ Trust $name") }
            }
        }
        items(p.trusted.toList()) { name ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name)
                TextButton({ Store.setPolicy(p.copy(trusted = p.trusted - name)) }) { Text("Remove") }
            }
        }

        item { Text("Activity log", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
        item { TextButton({ Store.clearLog() }) { Text("Clear log") } }
        if (Store.log.isEmpty()) item { Text("No activity yet.") }
        items(Store.log.toList()) { e: ActivityEntry ->
            Text("${Store.fmt(e.time)}  ${e.text}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Switch(checked = value, onCheckedChange = onChange)
    }
}
