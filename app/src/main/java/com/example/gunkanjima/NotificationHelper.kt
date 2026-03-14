package com.example.gunkanjima

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_ID   = "gunkanjima_alerts"
    private const val CHANNEL_NAME = "軍艦島予約アラート"

    private val COMPANY_URLS = mapOf(
        "軍艦島コンシェルジュ" to "https://www.gunkanjima-concierge.com/cgi/web/?c=reserve-1",
        "高島海上交通"         to "https://www.gunkanjima-cruise.jp/reserve_input.php",
        "やまさ海運"           to "https://order.gunkan-jima.net/yamasa",
        "シーマン商会"         to "https://www.gunkanjima-tour-reserve.jp/reserve_input.php?course=1",
        "第七ゑびす丸"         to "https://mikata.in/nagasaki-tours/reservations/new?plan_id=2720"
    )

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "軍艦島ツアーの予約空き通知"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun sendNotification(context: Context, title: String, message: String, company: String = "") {
        val url = COMPANY_URLS[company]
        val intent = if (url != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }

        val notifId = if (company.isNotEmpty()) company.hashCode() else 1001

        val pendingIntent = PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }
}
