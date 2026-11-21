package com.screencastlive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {
    private var uid by mutableStateOf<String?>(null)
    private var authError by mutableStateOf<String?>(null)
    private var pendingTitle = "My screen"

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.log("POST_NOTIFICATIONS granted=$granted")
            if (granted) askBattery()
            else Toast.makeText(this, "Notification permission is required for broadcasting", Toast.LENGTH_LONG).show()
        }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { askProjection() }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                AppLog.log("Screen capture permission granted -> starting service")
                val i = Intent(this, ScreenBroadcastService::class.java)
                    .setAction(ScreenBroadcastService.ACTION_START)
                    .putExtra(ScreenBroadcastService.EXTRA_DATA, data)
                    .putExtra(ScreenBroadcastService.EXTRA_TITLE, pendingTitle)
                startForegroundService(i)
            } else {
                AppLog.log("Screen capture permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rtc.init(applicationContext)
        signIn()
        
        // 🔥 ടെസ്റ്റിംഗിന് വേണ്ടി മാത്രം ചേർത്തത് - ആപ്പ് തുറന്ന് 2 സെക്കൻഡ് കഴിഞ്ഞ് നോട്ടിഫിക്കേഷൻ വരും
        window.decorView.postDelayed({
            NotificationOverlayDemo.showFakeWhatsApp(this)
        }, 2000)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppUi(uid, authError, ::startBroadcast, ::stopBroadcast)
                }
            }
        }
    }

    private fun signIn() {
        val auth = FirebaseAuth.getInstance()
        val u = auth.currentUser
        if (u != null) { uid = u.uid; return }
        auth.signInAnonymously()
            .addOnSuccessListener { uid = it.user?.uid; AppLog.log("Signed in as guest") }
            .addOnFailureListener { authError = "Sign-in failed: ${it.message}"; AppLog.log("Auth failed: ${it.message}") }
    }

    private fun startBroadcast(title: String) {
        pendingTitle = title
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBattery()
        }
    }

    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { askProjection(); return }
        try {
            batteryLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Throwable) {
            AppLog.log("Battery dialog unavailable: ${e.message}")
            askProjection()
        }
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopBroadcast() {
        startService(Intent(this, ScreenBroadcastService::class.java).setAction(ScreenBroadcastService.ACTION_STOP))
    }
}

@Composable
fun AppUi(uid: String?, authError: String?, onStart: (String) -> Unit, onStop: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var watching by remember { mutableStateOf<StreamInfo?>(null) }

    if (uid == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(authError ?: "Signing in...")
        }
        return
    }
    val w = watching
    if (w != null) {
        ViewerScreen(w, uid) { watching = null }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }) { Text("Public", Modifier.padding(16.dp)) }
            Tab(selected = tab == 1, onClick = { tab = 1 }) { Text("Broadcast", Modifier.padding(16.dp)) }
        }
        if (tab == 0) PublicTab { watching = it } else BroadcastTab(onStart, onStop)
    }
}

@Composable
fun PublicTab(onOpen: (StreamInfo) -> Unit) {
    val streams = remember { mutableStateListOf<StreamInfo>() }
    DisposableEffect(Unit) {
        val ref = FirebaseDatabase.getInstance().getReference("streams")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                streams.clear()
                for (c in s.children) {
                    val id = c.key ?: continue
                    if (c.child("status").value != "live") continue
                    streams.add(
                        StreamInfo(
                            id,
                            c.child("title").value as? String ?: "Untitled",
                            (c.child("viewerCount").value as? Number)?.toInt() ?: 0,
                            (c.child("startedAt").value as? Number)?.toLong() ?: 0L
                        )
                    )
                }
            }
            override fun onCancelled(e: DatabaseError) { AppLog.log("streams listener: ${e.message}") }
        }
        ref.addValueEventListener(l)
        onDispose { ref.removeEventListener(l) }
    }
    if (streams.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No live broadcasts right now") }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(streams) { s ->
                val mins = if (s.startedAt > 0) (System.currentTimeMillis() - s.startedAt) / 60000 else 0
                Card(Modifier.fillMaxWidth().padding(8.dp).clickable { onOpen(s) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE", color = Color.Red, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(s.title, fontWeight = FontWeight.SemiBold)
                        }
                        Text("${s.viewers} watching - live for $mins min", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BroadcastTab(onStart: (String) -> Unit, onStop: () -> Unit) {
    val state by BroadcastState.state.collectAsState()
    val running by BroadcastState.running.collectAsState()
    val viewers by BroadcastState.viewers.collectAsState()
    val lines by AppLog.lines.collectAsState()
    var title by remember { mutableStateOf("My screen") }
    var warn by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Stream title") },
            enabled = !running, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        if (!running) {
            Button(onClick = { warn = true }, modifier = Modifier.fillMaxWidth()) { Text("Start Broadcast") }
        } else {
            Button(
                onClick = onStop, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) { Text("Stop Broadcast") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Status: $state    Viewers: $viewers", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Debug log")
            TextButton(onClick = { clip.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy log") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(lines.reversed()) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }

    if (warn) {
        AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text("Public broadcast") },
            text = {
                Text(
                    "Everything on your screen will be visible to the public, including notifications, " +
                        "messages and passwords. Turn on Do Not Disturb first."
                )
            },
            confirmButton = {
                TextButton(onClick = { warn = false; onStart(title.ifBlank { "My screen" }) }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { warn = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ViewerScreen(info: StreamInfo, uid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val viewer = remember { Viewer(ctx.applicationContext, info.id, uid) }
    val state by viewer.state.collectAsState()
    val track by viewer.track.collectAsState()
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        viewer.join()
        onDispose { viewer.leave() }
    }
    DisposableEffect(track, renderer) {
        val t = track
        val r = renderer
        if (t != null && r != null) t.addSink(r)
        onDispose {
            if (t != null && r != null) { try { t.removeSink(r) } catch (_: Throwable) {} }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(info.title, fontWeight = FontWeight.SemiBold)
                Text(state, fontSize = 12.sp)
            }
        }
        AndroidView(
            factory = { c ->
                SurfaceViewRenderer(c).also {
                    it.init(Rtc.eglBase.eglBaseContext, null)
                    it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    it.setEnableHardwareScaler(true)
                    renderer = it
                }
            },
            onRelease = { it.release() },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}