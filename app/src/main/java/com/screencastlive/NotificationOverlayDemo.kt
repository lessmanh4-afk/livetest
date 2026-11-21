package com.screencastlive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

object NotificationOverlayDemo {

    private const val CHANNEL_WHATSAPP = "demo_overlay_whatsapp_v2"
    private const val ID_WHATSAPP      = 9002

    fun showFakeWhatsApp(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannel(nm, CHANNEL_WHATSAPP, "Messages")

        // 🔥 ഇവിടെയാണ് "ScreenCast Live" എന്നത് മാറ്റി "WhatsApp" എന്ന് കാണിക്കുന്നത്
        val customView = RemoteViews(context.packageName, R.layout.fake_whatsapp_notification)
        customView.setTextViewText(R.id.tv_app_name, "WhatsApp")
        customView.setTextViewText(R.id.tv_time, "now")
        customView.setTextViewText(R.id.tv_title, "Rahul")
        customView.setTextViewText(R.id.tv_content, "Hey, are you free for a call?")

        val n = NotificationCompat.Builder(context, CHANNEL_WHATSAPP)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

        nm.notify(ID_WHATSAPP, n)
        AppLog.log("DEMO: fake WhatsApp notification posted (Custom Layout)")
    }

    fun hideAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_WHATSAPP)
        AppLog.log("DEMO: overlay notifications cleared")
    }

    private fun ensureChannel(nm: NotificationManager, id: String, name: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                id, name, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Security demo channel (overlay attack simulation)"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}