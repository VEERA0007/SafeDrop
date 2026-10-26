package com.safedrop.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.text.DateFormat
import java.util.Date

// ===================================================================== App shell

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeDropApp(manager: NearbyManager, store: ParentalStore, permissions: Array<String>) {
    val state by manager.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var parentUnlocked by remember { mutableStateOf(false) }

    // Leaving the Parent tab always locks it again.
    LaunchedEffect(tab) { if (tab != 1) parentUnlocked = false }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SafeDrop") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    label = { Text("Share") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    label = { Text("Parent") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) {
                ShareScreen(manager, store, state, permissions)
            } else {
                ParentArea(manager, store, state, parentUnlocked) { parentUnlocked = it }
            }
        }
    }

    state.incoming?.let { req ->
        PinDialog(
            title = "Incoming file",
            message = "${req.peer} wants to send \"${req.fileName}\" (${formatSize(req.size)}).\n\n${req.reason}.",
            store = store,
            confirmLabel = "Approve",
            dismissLabel = "Decline",
            onSuccess = { manager.respondIncoming(true) },
            onDismiss = { manager.respondIncoming(false) }
        )
    }
}

// ===================================================================== Share tab

@Composable
fun ShareScreen(manager: NearbyManager, store: ParentalStore, state: UiState, permissions: Array<String>) {
    val ctx = LocalContext.current
    val hasPin = store.hasPin

    var granted by remember { mutableStateOf(hasAllPermissions(ctx, permissions)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAllPermissions(ctx, permissions)
    }

    var target by remember { mutableStateOf<Peer?>(null) }
    var pendingSend by remember { mutableStateOf<Triple<Peer, Uri, FileInfo>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val peer = target
        if (uri != null && peer != null) {
            val info = manager.describe(uri)
            when (val d = manager.checkSend(peer, info)) {
                is Decision.Allow -> manager.send(peer, uri, info)
                is Decision.AskParent -> pendingSend = Triple(peer, uri, info)
                is Decision.Block -> manager.blockedSend(peer, info, d.reason)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!hasPin) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Parent setup needed", style = MaterialTheme.typography.titleMedium)
                        Text("A parent must open the Parent tab and create a PIN before SafeDrop can be used.")
                    }
                }
            }
        }

        if (!granted) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SafeDrop needs Nearby devices / Bluetooth permission to find devices around you.")
                        Button(onClick = { permLauncher.launch(permissions) }) { Text("Grant permissions") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Receive files", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Visible to nearby SafeDrop devices as \"${store.deviceName}\"",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = state.receiving,
                        enabled = hasPin,
                        onCheckedChange = { on ->
                            if (on) {
                                if (granted) manager.startReceiving() else permLauncher.launch(permissions)
                            } else manager.stopReceiving()
                        }
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    when {
                        state.discovering -> manager.stopSearching()
                        granted -> manager.startSearching()
                        else -> permLauncher.launch(permissions)
                    }
                },
                enabled = hasPin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.discovering) "Stop searching" else "Find nearby devices")
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                state.progress?.let { p ->
                    Text(state.progressLabel, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (state.discovering && state.peers.isEmpty()) {
            item { Text("No devices found yet. Make sure the other device has \"Receive files\" turned on.") }
        }

        items(state.peers, key = { it.endpointId }) { peer ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        target = peer
                        picker.launch("*/*")
                    }) { Text("Send file") }
                }
            }
        }

        item {
            Text("Received files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.received.isEmpty()) {
            item { Text("Nothing received yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(state.received, key = { it.absolutePath }) { file ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.name)
                    Text(formatSize(file.length()), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { openFile(ctx, file) }) { Text("Open") }
            }
        }
    }

    pendingSend?.let { (peer, uri, info) ->
        PinDialog(
            title = "Parent approval",
            message = "Send \"${info.name}\" (${formatSize(info.size)}) to ${peer.name}?",
            store = store,
            confirmLabel = "Approve",
            dismissLabel = "Cancel",
            onSuccess = {
                pendingSend = null
                manager.send(peer, uri, info)
            },
            onDismiss = {
                pendingSend = null
                manager.cancelledSend(peer, info)
            }
        )
    }
}

private fun hasAllPermissions(ctx: Context, permissions: Array<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

private fun openFile(ctx: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Couldn't open the file", Toast.LENGTH_SHORT).show()
    }
}

fun formatSize(b: Long): String = when {
    b < 0 -> "unknown size"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1048576.0)
}

// ===================================================================== PIN dialog

@Composable
fun PinDialog(
    title: String,
    message: String,
    store: ParentalStore,
    confirmLabel: String,
    dismissLabel: String,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                    label = { Text("Parent PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (store.tryPin(pin)) {
                    onSuccess()
                } else {
                    val ms = store.lockRemainingMs()
                    error = if (ms > 0) "Too many tries. Wait ${ms / 1000 + 1}s" else "Wrong PIN"
                    pin = ""
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

// ===================================================================== Parent tab

@Composable
fun ParentArea(
    manager: NearbyManager,
    store: ParentalStore,
    state: UiState,
    unlocked: Boolean,
    setUnlocked: (Boolean) -> Unit
) {
    when {
        !store.hasPin -> SetPinScreen(store) { setUnlocked(true) }
        !unlocked -> EnterPinScreen(store) { setUnlocked(true) }
        else -> ParentSettings(manager, store, state) { setUnlocked(false) }
    }
}

@Composable
fun SetPinScreen(store: ParentalStore, onDone: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Create a parent PIN", style = MaterialTheme.typography.headlineSmall)
        Text("This PIN unlocks the settings and approves files. Pick one your child can't guess (4 to 8 digits).")
        PinField("New PIN", pin) { pin = it }
        PinField("Repeat PIN", again) { again = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            when {
                pin.length < 4 -> error = "Use at least 4 digits"
                pin != again -> error = "The two PINs don't match"
                else -> {
                    store.setPin(pin)
                    onDone()
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save PIN") }
    }
}

@Composable
fun EnterPinScreen(store: ParentalStore, onDone: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Parent area", style = MaterialTheme.typography.headlineSmall)
        Text("Enter the parent PIN to change settings.")
        PinField("Parent PIN", pin) { pin = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (store.tryPin(pin)) {
                onDone()
            } else {
                val ms = store.lockRemainingMs()
                error = if (ms > 0) "Too many tries. Wait ${ms / 1000 + 1}s" else "Wrong PIN"
                pin = ""
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun ParentSettings(manager: NearbyManager, store: ParentalStore, state: UiState, onLock: () -> Unit) {
    var showChangePin by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Parent controls", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onLock) { Text("Lock") }
        }

        DeviceNameSection(store)
        RulesSection(store)
        CategoriesSection(store)
        MaxSizeSection(store)
        ApprovedDevicesSection(store)
        QuietHoursSection(store)

        Section("Activity log") {
            if (state.log.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            state.log.take(50).forEach { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "${if (e.direction == "IN") "Received" else "Sent"}: ${e.fileName}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("${e.peer} | ${formatSize(e.size)} | ${e.outcome}", style = MaterialTheme.typography.bodySmall)
                    Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
            if (state.log.isNotEmpty()) {
                TextButton(onClick = {
                    store.clearLog()
                    manager.refreshLog()
                }) { Text("Clear log") }
            }
        }

        OutlinedButton(onClick = { showChangePin = true }, modifier = Modifier.fillMaxWidth()) { Text("Change PIN") }
    }

    if (showChangePin) ChangePinDialog(store) { showChangePin = false }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DeviceNameSection(store: ParentalStore) {
    var name by remember { mutableStateOf(store.deviceName) }
    Section("This device's name") {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(20) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Shown to nearby devices. Turn receiving off and on again after changing it.") }
        )
        Button(onClick = { if (name.isNotBlank()) store.deviceName = name }) { Text("Save name") }
    }
}

@Composable
private fun RulesSection(store: ParentalStore) {
    var receive by remember { mutableStateOf(store.receiveMode) }
    var send by remember { mutableStateOf(store.sendMode) }
    Section("Who decides?") {
        ModePicker("Files coming in", receive) { receive = it; store.receiveMode = it }
        HorizontalDivider()
        ModePicker("Files going out", send) { send = it; store.sendMode = it }
    }
}

@Composable
private fun ModePicker(title: String, current: Mode, onChange: (Mode) -> Unit) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Mode.values().forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { onChange(m) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = current == m, onClick = { onChange(m) })
                Text(m.label)
            }
        }
    }
}

@Composable
private fun CategoriesSection(store: ParentalStore) {
    var blocked by remember { mutableStateOf(store.blockedCategories) }
    Section("Blocked file types") {
        Category.values().forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(c.label, Modifier.weight(1f))
                Switch(checked = c in blocked, onCheckedChange = { on ->
                    blocked = if (on) blocked + c else blocked - c
                    store.blockedCategories = blocked
                })
            }
        }
    }
}

@Composable
private fun MaxSizeSection(store: ParentalStore) {
    var mb by remember { mutableFloatStateOf(store.maxSizeMb.toFloat()) }
    Section("Largest file allowed: ${mb.toInt()} MB") {
        Slider(
            value = mb, onValueChange = { mb = it },
            valueRange = 1f..500f,
            onValueChangeFinished = { store.maxSizeMb = mb.toInt() }
        )
    }
}

@Composable
private fun ApprovedDevicesSection(store: ParentalStore) {
    var devices by remember { mutableStateOf(store.allowedPeers.sorted()) }
    var newName by remember { mutableStateOf("") }
    Section("Approved devices") {
        Text(
            if (devices.isEmpty()) "Empty: any nearby SafeDrop device can connect (files still follow the rules above)."
            else "Only these devices can send to or receive from this phone.",
            style = MaterialTheme.typography.bodySmall
        )
        devices.forEach { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(d, Modifier.weight(1f))
                IconButton(onClick = {
                    val next = store.allowedPeers - d
                    store.allowedPeers = next
                    devices = next.sorted()
                }) { Icon(Icons.Default.Delete, contentDescription = "Remove $d") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName, onValueChange = { newName = it.take(20) },
                label = { Text("Device name exactly as shown") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                if (newName.isNotBlank()) {
                    val next = store.allowedPeers + newName.trim()
                    store.allowedPeers = next
                    devices = next.sorted()
                    newName = ""
                }
            }) { Icon(Icons.Default.Add, contentDescription = "Add device") }
        }
    }
}

@Composable
private fun QuietHoursSection(store: ParentalStore) {
    var on by remember { mutableStateOf(store.quietEnabled) }
    var start by remember { mutableIntStateOf(store.quietStartHour) }
    var end by remember { mutableIntStateOf(store.quietEndHour) }
    Section("Quiet hours") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("No sharing during quiet hours", Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it; store.quietEnabled = it })
        }
        if (on) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HourPicker("From", start) { start = it; store.quietStartHour = it }
                HourPicker("Until", end) { end = it; store.quietEndHour = it }
            }
        }
    }
}

@Composable
private fun HourPicker(label: String, hour: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label ${"%02d".format(hour)}:00") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { h ->
                DropdownMenuItem(
                    text = { Text("%02d:00".format(h)) },
                    onClick = { onChange(h); open = false }
                )
            }
        }
    }
}

@Composable
private fun ChangePinDialog(store: ParentalStore, onClose: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Change PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField("New PIN", pin) { pin = it }
                PinField("Repeat PIN", again) { again = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    pin.length < 4 -> error = "Use at least 4 digits"
                    pin != again -> error = "The two PINs don't match"
                    else -> {
                        store.setPin(pin)
                        onClose()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
