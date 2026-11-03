package com.example.hexcro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.hexcro.HexCroService
import com.example.hexcro.MainActivity
import com.example.hexcro.TapInjector
import com.example.hexcro.IHexCroCallback
import rikka.shizuku.Shizuku
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HexCroForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "HexCro_running"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.HexCro.START"
        const val ACTION_STOP = "com.example.HexCro.STOP"
    }

    interface Listener {
        fun onLogLine(line: String)
        fun onLogError(error: String)
        fun onStateChanged(running: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): HexCroForegroundService = this@HexCroForegroundService
    }

    private val binder = LocalBinder()
    private var HexCro: IHexCroService? = null
    private var shizukuBound = false
    private var listener: Listener? = null
    private val recentLines = ArrayDeque<String>()
    private var running = false
    private val antiAfkHandler = Handler(Looper.getMainLooper())
    private var antiAfkRunnable: Runnable? = null
    private var antiAfkArmed = false

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, HexCroService::class.java.name)
        )
            .daemon(false)
            .version(3)
            .processNameSuffix("HexCro")
            .tag("HexCroService")
    }

    private val logCallback = object : IHexCroCallback.Stub() {
        override fun onLogLine(line: String?) {
            val l = line ?: return
            synchronized(recentLines) {
                recentLines.addLast(l)
                while (recentLines.size > 500) recentLines.removeFirst()
            }
            listener?.onLogLine(l)
            when {
                l.startsWith("BIOME:")     -> armAntiAfk()
                l.startsWith("BIOME_END:") -> { /* don't disarm — timer keeps counting */ }
            }
        }
        override fun onLogError(error: String?) {
            listener?.onLogError(error ?: "unknown")
        }
    }

    private fun sendAntiAfkWebhook(success: Boolean) {
        val prefs = getSharedPreferences("HexCro", Context.MODE_PRIVATE)
        val webhook = prefs.getString("webhook", "").orEmpty().trim()
        if (webhook.isEmpty()) return

        Thread {
            try {
                val embed = JSONObject().apply {
                    put("title", if (success) "Anti-AFK tap sent" else "⚠️ Anti-AFK tap failed")
                    put("color", if (success) 0x6C5CE7 else 0xEF5350)
                    put("footer", JSONObject().put("text", "HexCro | v1.1.0"))
                }
                val payload = JSONObject().apply { put("embeds", JSONArray().put(embed)) }

                val conn = URL(webhook).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "HexCro")
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                Log.i("HexCro", "FG: anti-AFK webhook -> ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("HexCro", "FG: anti-AFK webhook failed", e)
            }
        }.start()
    }

    private val shizukuConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i("HexCro", "FG: shizuku connected")
            shizukuBound = true
            HexCro = IHexCroService.Stub.asInterface(service)

            val prefs = getSharedPreferences("HexCro", Context.MODE_PRIVATE)
            val webhook = prefs.getString("webhook", "").orEmpty()
            val server = prefs.getString("server", "").orEmpty()

            try {
                HexCro?.setConfig(webhook, server)
            } catch (e: Exception) {
                Log.e("HexCro", "FG: setConfig failed", e)
            }

            try {
                HexCro?.startLogcat(logCallback)
                running = true
                listener?.onStateChanged(true)
            } catch (e: Exception) {
                Log.e("HexCro", "FG: startLogcat failed", e)
                listener?.onLogError(e.message ?: "startLogcat failed")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w("HexCro", "FG: shizuku disconnected")
            disarmAntiAfk()
            shizukuBound = false
            HexCro = null
            running = false
            listener?.onStateChanged(false)
        }
    }

    private fun armAntiAfk() {
        if (antiAfkArmed) return

        val prefs = getSharedPreferences("HexCro", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("antiAfkEnabled", false)) {
            Log.i("HexCro", "FG: anti-AFK disabled in prefs, not arming")
            return
        }

        // Hard gate: no accessibility, no arming
        if (!TapInjector.canTap()) {
            Log.w("HexCro", "FG: anti-AFK blocked — accessibility service not connected")
            listener?.onLogLine("ANTI_AFK_BLOCKED no_accessibility")
            return
        }

        val intervalMs = prefs.getInt("antiAfkInterval", 10) * 60_000L
        antiAfkArmed = true
        antiAfkRunnable = object : Runnable {
            override fun run() {
                if (!antiAfkArmed) return
                if (!TapInjector.canTap()) {
                    Log.w("HexCro", "FG: anti-AFK tap skipped — accessibility lost")
                    listener?.onLogLine("ANTI_AFK_BLOCKED no_accessibility")
                    disarmAntiAfk()
                    return
                }
                TapInjector.tapSavedAsync(this@HexCroForegroundService) { ok ->
                    listener?.onLogLine("ANTI_AFK_TAP ${if (ok) "sent" else "failed"}")
                    sendAntiAfkWebhook(ok)
                }
                antiAfkHandler.postDelayed(this, intervalMs)
            }
        }
        antiAfkHandler.postDelayed(antiAfkRunnable!!, intervalMs)
        Log.i("HexCro", "FG: anti-AFK armed, first tap in ${intervalMs / 60_000} min")
        listener?.onLogLine("ANTI_AFK_ARMED")
    }

    private fun disarmAntiAfk() {
        antiAfkRunnable?.let { antiAfkHandler.removeCallbacks(it) }
        antiAfkRunnable = null
        antiAfkArmed = false
        Log.i("HexCro", "FG: anti-AFK disarmed")
    }

    // ─── Public API used by MainActivity via LocalBinder ───

    fun setAntiAfkEnabled(enabled: Boolean) {
        getSharedPreferences("HexCro", Context.MODE_PRIVATE)
            .edit().putBoolean("antiAfkEnabled", enabled).apply()
        if (enabled) {
            if (running) armAntiAfk()
        } else {
            disarmAntiAfk()
        }
        listener?.onLogLine("ANTI_AFK_ENABLED $enabled")
    }

    fun setAntiAfkInterval(minutes: Int) {
        getSharedPreferences("HexCro", Context.MODE_PRIVATE)
            .edit().putInt("antiAfkInterval", minutes).apply()
        if (antiAfkArmed) {
            disarmAntiAfk()
            armAntiAfk()
        }
        listener?.onLogLine("ANTI_AFK_INTERVAL $minutes")
    }

    fun isAntiAfkArmed(): Boolean = antiAfkArmed

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMacro()
            ACTION_STOP -> stopMacro()
        }
        return START_STICKY
    }

    private fun startMacro() {
        if (running) return

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!Shizuku.pingBinder()) {
            Log.w("HexCro", "FG: Shizuku not running")
            listener?.onLogError("Shizuku is not running")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (Shizuku.checkSelfPermission() != 0) {
            Log.w("HexCro", "FG: permission not granted")
            listener?.onLogError("Shizuku permission not granted")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            Shizuku.bindUserService(serviceArgs, shizukuConn)
        } catch (e: Exception) {
            Log.e("HexCro", "FG: bind failed", e)
            listener?.onLogError(e.message ?: "bind failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopMacro() {
        Log.i("HexCro", "FG: stopMacro() called")

        disarmAntiAfk()

        try {
            if (HexCro == null) {
                Log.e("HexCro", "FG: Shizuku service reference is NULL")
            } else {
                Log.i("HexCro", "FG: calling remote stopLogcat()")
                HexCro?.stopLogcat()
                Log.i("HexCro", "FG: remote stopLogcat() returned successfully")
            }
        } catch (e: Exception) {
            Log.e("HexCro", "FG: remote stopLogcat() FAILED", e)
        }

        if (Shizuku.pingBinder() && shizukuBound) {
            try {
                Log.i("HexCro", "FG: unbinding Shizuku service")
                Shizuku.unbindUserService(serviceArgs, shizukuConn, true)
            } catch (e: Exception) {
                Log.e("HexCro", "FG: unbind failed", e)
            }
        }

        shizukuBound = false
        HexCro = null
        running = false

        listener?.onStateChanged(false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i("HexCro", "FG: stopMacro() finished")
    }

    fun isRunning(): Boolean = running

    fun setListener(l: Listener?) {
        listener = l
        l?.onStateChanged(running)
    }

    fun getRecentLines(): List<String> = synchronized(recentLines) { recentLines.toList() }

    fun clearLines() = synchronized(recentLines) { recentLines.clear() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HexCro Running",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while HexCro is monitoring biomes"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, HexCroForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HexCro is running")
            .setContentText("Monitoring biomes in the background")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}