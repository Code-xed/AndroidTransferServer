package com.localtransfer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localtransfer.server.HttpServer
import com.localtransfer.server.RequestRouter
import com.localtransfer.storage.LocalFileStorageProvider
import com.localtransfer.storage.SafStorageProvider
import java.io.File
import java.net.NetworkInterface

class TransferForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.localtransfer.action.START"
        const val ACTION_STOP = "com.localtransfer.action.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_SHARED_TREE_URI = "shared_tree_uri"
        private const val CHANNEL_ID = "transfer_server_channel"
        private const val NOTIF_ID = 1001

        @Volatile var isServerRunning: Boolean = false
            private set
        @Volatile var currentPort: Int = 8080
            private set
    }

    private var httpServer: HttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
                val treeUriString = intent?.getStringExtra(EXTRA_SHARED_TREE_URI)
                startServerInternal(port, treeUriString)
            }
        }
        return START_STICKY
    }

    private fun startServerInternal(port: Int, treeUriString: String?) {
        if (isServerRunning) return

        val webRootDir = ensureWebRootAssets()
        val webRootProvider = LocalFileStorageProvider(webRootDir)

        val sharedProvider = if (treeUriString != null) {
            SafStorageProvider(applicationContext, Uri.parse(treeUriString))
        } else {
            // No folder selected yet -- fall back to an app-private directory so the
            // server still starts and /files/ isn't a hard error.
            val fallbackDir = File(filesDir, "SharedDefault").apply { mkdirs() }
            LocalFileStorageProvider(fallbackDir)
        }

        val router = RequestRouter(webRootProvider, sharedProvider, port) { getLocalIpAddress() }
        val server = HttpServer(port, router)
        server.start()
        httpServer = server
        currentPort = port
        isServerRunning = true

        startForeground(NOTIF_ID, buildNotification(port))
    }

    private fun stopServerInternal() {
        httpServer?.stop()
        httpServer = null
        isServerRunning = false
    }

    override fun onDestroy() {
        stopServerInternal()
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        createChannelIfNeeded()
        val stopIntent = Intent(this, TransferForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transfer Server")
            .setContentText("Running on ${getLocalIpAddress()}:$port")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transfer Server", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** Copies bundled default web assets into internal storage on first run so the
     *  web root is a plain, user-editable directory (satisfies "replace the whole
     *  page without touching the app" -- editing via a file manager / adb works too). */
    private fun ensureWebRootAssets(): File {
        val dest = File(filesDir, "webroot")
        if (dest.exists() && dest.listFiles()?.isNotEmpty() == true) return dest
        dest.mkdirs()
        val am = assets
        for (name in am.list("webroot") ?: emptyArray()) {
            am.open("webroot/$name").use { input ->
                File(dest, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        // Fallback: enumerate interfaces for a non-loopback IPv4 address (works for
        // hotspot/other network types where WifiManager's info is unreliable).
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }
}
