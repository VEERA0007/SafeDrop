package com.safedrop.parent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.safedrop.core.newPairingCode
import kotlinx.coroutines.delay
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ParentRoot() } }
    }
}

private fun ago(t: Long): String {
    if (t <= 0) return "never"
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun ParentRoot() {
    var configured by remember { mutableStateOf(Repo.configured()) }
    if (!configured) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            SetupScreen { configured = true }
        }
    } else {
        Dashboard()
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var db by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Text("SafeDrop Parent", style = MaterialTheme.typography.headlineMedium)
    Text("Step 1: create a free Firebase project with a Realtime Database (see the README), then paste its address here.")
    OutlinedTextField(db, { db = it }, label = { Text("Database address (https://...firebaseio.com)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(name, { name = it }, label = { Text("Child's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
    Button({
        if (!db.trim().startsWith("https://") || name.isBlank()) err = "Enter the https address and your child's name."
        else { Repo.save(db, newPairingCode(), name); onDone() }
    }, modifier = Modifier.padding(top = 8.dp)) { Text("Create pairing code") }
}

@Composable
fun Dashboard() {
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            try { Repo.refresh() } catch (e: Exception) { }
            delay(10_000)
        }
    }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("🏠") }, label = { Text("Home") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("📥") },
                label = { Text(if (Repo.requests.isEmpty()) "Files" else "Files (${Repo.requests.size})") })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("📜") }, label = { Text("Activity") })
            NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Text("📱") }, label = { Text("Apps") })
            NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Text("🎛") }, label = { Text("Rules") })
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> HomeTab()
                1 -> FilesTab()
                2 -> ActivityTab()
                3 -> AppsTab()
                else -> RulesTab()
            }
        }
    }
}

@Composable
fun HomeTab() {
    val ctx = LocalContext.current
    val st = Repo.status
    Text(Repo.kidName, style = MaterialTheme.typography.headlineMedium)
    if (st == null) {
        Text(if (Repo.reachable == false) "Cannot reach the database. Check internet." else "Waiting for ${Repo.kidName}'s phone to check in.")
        PairingCard()
        return
    }
    val t = st.optLong("t")
    Text("Last check-in: ${ago(t)}  (${st.optString("model")})")
    val bat = st.optInt("battery", -1)
    Text("Battery: ${if (bat < 0) "?" else "$bat%"}${if (st.optBoolean("charging")) " (charging)" else ""}")
    Text("Screen time today: ${st.optInt("usageTotalMin")} min")

    if (!st.optBoolean("locPerm", true)) Warn("Location is off on the child's phone.")
    if (!st.optBoolean("usageAccess", true)) Warn("App usage access is off, so screen time and app limits do not work.")
    if (!st.optBoolean("adminActive", true)) Warn("Uninstall protection / locking is off on the child's phone.")

    Text("Location", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (st.has("lat")) {
        val lat = st.optDouble("lat"); val lng = st.optDouble("lng")
        Text("Updated ${ago(st.optLong("locT"))}, accuracy about ${st.optInt("acc")} m")
        Button({
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(Repo.kidName)})")
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng")))
            }
        }) { Text("Open in Maps") }
    } else Text("No location yet.")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton({ Repo.locateNow() }) { Text("Locate now") }
        OutlinedButton({ Repo.lockNow() }) { Text("Lock phone now") }
    }

    AlertsCard()
    PairingCard()
}

@Composable
fun Warn(text: String) {
    Text("⚠️ $text", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun AlertsCard() {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
    }
    Text("Instant alerts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text("Get a notification the moment a file needs approval or the phone goes offline.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({
            if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
        }) { Text("Turn on") }
        OutlinedButton({ ctx.stopService(Intent(ctx, WatchService::class.java)) }) { Text("Turn off") }
    }
}

@Composable
fun PairingCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Pair the child's phone", style = MaterialTheme.typography.titleMedium)
            Text("Install SafeDrop Kids on the child's phone, open Setup, and enter:")
            Text("Database address:\n${Repo.db}")
            TextButton({ copy(ctx, "db", Repo.db) }) { Text("Copy address") }
            Text("Pairing code:  ${Repo.code}", style = MaterialTheme.typography.titleMedium)
            TextButton({ copy(ctx, "code", Repo.code) }) { Text("Copy code") }
        }
    }
}

@Composable
fun FilesTab() {
    Text("File requests", style = MaterialTheme.typography.headlineSmall)
    if (Repo.requests.isEmpty()) Text("Nothing waiting for approval.")
    Repo.requests.forEach { r ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(if (r.direction == "IN") "Receiving from ${r.peer}" else "Wants to send to ${r.peer}", style = MaterialTheme.typography.labelLarge)
                Text("${r.fileName}  (${r.size / 1024} KB)")
                Text(ago(r.time), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button({ Repo.decide(r.id, "approve") }) { Text("Approve") }
                    OutlinedButton({ Repo.decide(r.id, "reject") }) { Text("Reject") }
                }
            }
        }
    }
}

@Composable
fun ActivityTab() {
    Text("Activity", style = MaterialTheme.typography.headlineSmall)
    if (Repo.log.isEmpty()) Text("No activity yet.")
    Repo.log.forEach {
        Text("${ago(it.time)}: ${it.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
fun AppsTab() {
    val st = Repo.status
    Text("Screen time today", style = MaterialTheme.typography.headlineSmall)
    val usage = st?.optJSONArray("usage")
    if (usage == null || usage.length() == 0) Text("No usage data yet. Make sure usage access is on in the child's phone.")
    else for (i in 0 until usage.length()) {
        val o = usage.getJSONObject(i)
        Text("${o.optString("app")}: ${o.optInt("min")} min")
    }
    Text("Block apps", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
    Text("A blocked app locks the screen when it is opened. Needs uninstall protection turned on.", style = MaterialTheme.typography.bodySmall)
    val apps = st?.optJSONArray("apps")
    if (apps == null) Text("The app list arrives within about 10 minutes of pairing.")
    else for (i in 0 until apps.length()) {
        val o = apps.getJSONObject(i)
        val pkg = o.optString("p")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(o.optString("n"), modifier = Modifier.weight(1f))
            Switch(checked = pkg in Repo.control.blockedApps, onCheckedChange = { on ->
                Repo.update { it.copy(blockedApps = if (on) it.blockedApps + pkg else it.blockedApps - pkg) }
            })
        }
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun RulesTab() {
    val c = Repo.control
    val f = c.file
    Text("Time rules", style = MaterialTheme.typography.headlineSmall)

    var limit by remember(c.dailyLimitMin) { mutableStateOf(c.dailyLimitMin.toFloat()) }
    Text(if (limit.toInt() == 0) "Daily screen time: no limit" else "Daily screen time limit: ${limit.toInt() / 60} h ${limit.toInt() % 60} min")
    Slider(value = limit, onValueChange = { limit = (Math.round(it / 15f) * 15).toFloat() },
        onValueChangeFinished = { Repo.update { it.copy(dailyLimitMin = limit.toInt()) } }, valueRange = 0f..480f)

    SwitchRow("Bedtime (phone locks)", c.bedtimeOn) { on -> Repo.update { it.copy(bedtimeOn = on) } }
    if (c.bedtimeOn) {
        var s by remember(c.bedStartHour) { mutableStateOf(c.bedStartHour.toFloat()) }
        var e by remember(c.bedEndHour) { mutableStateOf(c.bedEndHour.toFloat()) }
        Text("From ${s.toInt()}:00 to ${e.toInt()}:00")
        Slider(value = s, onValueChange = { s = it }, onValueChangeFinished = { Repo.update { it.copy(bedStartHour = s.toInt()) } }, valueRange = 0f..23f)
        Slider(value = e, onValueChange = { e = it }, onValueChangeFinished = { Repo.update { it.copy(bedEndHour = e.toInt()) } }, valueRange = 0f..23f)
    }
    Text("Locking needs uninstall protection turned on in the child's phone. Calls still work.", style = MaterialTheme.typography.bodySmall)

    Text("File sharing rules", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
    SwitchRow("Rules on", f.enabled) { on -> Repo.update { it.copy(file = it.file.copy(enabled = on)) } }
    SwitchRow("Approve incoming files", f.approveIncoming) { on -> Repo.update { it.copy(file = it.file.copy(approveIncoming = on)) } }
    SwitchRow("Approve outgoing files", f.approveOutgoing) { on -> Repo.update { it.copy(file = it.file.copy(approveOutgoing = on)) } }
    SwitchRow("Trusted devices only", f.trustedOnly) { on -> Repo.update { it.copy(file = it.file.copy(trustedOnly = on)) } }

    var mb by remember(f.maxSizeMb) { mutableStateOf(f.maxSizeMb.toFloat()) }
    Text("Max file size: ${mb.toInt()} MB")
    Slider(value = mb, onValueChange = { mb = it }, onValueChangeFinished = { Repo.update { it.copy(file = it.file.copy(maxSizeMb = mb.toInt().coerceAtLeast(1))) } }, valueRange = 1f..500f)

    var ext by remember(f.blockedExt) { mutableStateOf(f.blockedExt.sorted().joinToString(", ")) }
    OutlinedTextField(ext, { ext = it }, label = { Text("Blocked file types (comma separated)") }, modifier = Modifier.fillMaxWidth())
    TextButton({
        val set = ext.split(",").map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
        Repo.update { it.copy(file = it.file.copy(blockedExt = set)) }
    }) { Text("Save file types") }

    var trusted by remember(f.trusted) { mutableStateOf(f.trusted.sorted().joinToString(", ")) }
    OutlinedTextField(trusted, { trusted = it }, label = { Text("Trusted device names (comma separated)") }, modifier = Modifier.fillMaxWidth())
    TextButton({
        val set = trusted.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        Repo.update { it.copy(file = it.file.copy(trusted = set)) }
    }) { Text("Save trusted devices") }
}
