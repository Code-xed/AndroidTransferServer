package com.localtransfer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.localtransfer.R
import com.localtransfer.service.TransferForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var sharedFolderText: TextView
    private lateinit var startStopButton: Button

    private val port = 8080

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_SHARED_URI, uri.toString()).apply()
            sharedFolderText.text = "Shared folder: ${uri.lastPathSegment}"
            Toast.makeText(this, "Folder selected. Restart the server to apply.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val KEY_SHARED_URI = "shared_tree_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("transfer_prefs", Context.MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        sharedFolderText = findViewById(R.id.sharedFolderText)
        startStopButton = findViewById(R.id.startStopButton)

        findViewById<Button>(R.id.selectFolderButton).setOnClickListener {
            pickFolderLauncher.launch(null)
        }
        findViewById<Button>(R.id.copyUrlButton).setOnClickListener { copyUrl() }
        startStopButton.setOnClickListener { toggleServer() }

        prefs.getString(KEY_SHARED_URI, null)?.let {
            sharedFolderText.text = "Shared folder: ${Uri.parse(it).lastPathSegment}"
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun toggleServer() {
        if (TransferForegroundService.isServerRunning) {
            val intent = Intent(this, TransferForegroundService::class.java).apply {
                action = TransferForegroundService.ACTION_STOP
            }
            startService(intent)
        } else {
            val sharedUri = prefs.getString(KEY_SHARED_URI, null)
            val intent = Intent(this, TransferForegroundService::class.java).apply {
                action = TransferForegroundService.ACTION_START
                putExtra(TransferForegroundService.EXTRA_PORT, port)
                sharedUri?.let { putExtra(TransferForegroundService.EXTRA_SHARED_TREE_URI, it) }
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        }
        // Give the service a moment to flip state, then refresh.
        urlText.postDelayed({ refreshStatus() }, 300)
    }

    private fun refreshStatus() {
        val running = TransferForegroundService.isServerRunning
        statusText.text = if (running) "● SERVER RUNNING" else "SERVER STOPPED"
        startStopButton.text = if (running) "STOP SERVER" else "START SERVER"
        urlText.text = if (running) "http://${getLocalIpAddress()}:${TransferForegroundService.currentPort}/" else ""
    }

    private fun copyUrl() {
        val text = urlText.text.toString()
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Server URL", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }
}
