package org.arthur.pigeon

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MessageCheckService : Service() {

    private var timer: Timer? = null
    private val CHANNEL_ID = "pigeon_channel"
    private val NOTIF_ID_FIXED = 1
    private val NOTIF_ID_NEW_MESSAGE = 2

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
        startCheckingMessages()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Verificação de Mensagens",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Canal para notificações do Pigeon"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val fixedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pigeon ativo")
            .setContentText("The Big Brother is watching your messages!")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID_FIXED, fixedNotification)
    }

    private fun startCheckingMessages() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                checkForNewMessages()
            }
        }, 0, 15000) // a cada 15 segundos
    }

    private fun checkForNewMessages() {
        try {
            val url = URL("https://thepigeon.com.br/API/check.php")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            // Usar cookies da WebView
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://thepigeon.com.br")
            if (cookies != null) {
                conn.setRequestProperty("Cookie", cookies)
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                if (json.optBoolean("temNovaMensagem", false)) {
                    val quem = json.optString("quemMandou", "alguém")
                    sendNotification("Nova mensagem!", "Você recebeu uma mensagem de $quem")
                }
            }

            conn.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("url", "https://thepigeon.com.br/mobilee/chat.php")
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(NOTIF_ID_NEW_MESSAGE, builder.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
