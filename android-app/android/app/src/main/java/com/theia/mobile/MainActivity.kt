package com.theia.mobile

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TheiaMainActivity"
        private const val HEALTH_CHECK_INTERVAL_MS = 1200L
        private const val HEALTH_CHECK_TIMEOUT_MS = 180_000L
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar

    private var healthCheckTimer: Timer? = null
    private var backendLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if onboarding is required
        val onboardingManager = OnboardingStateManager(this)
        if (!onboardingManager.isOnboardingComplete()) {
            // Launch onboarding flow
            val intent = Intent(this, OnboardingActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        progressBar = findViewById(R.id.progressBar)

        configureWebView()
        requestStoragePermissionsIfNeeded()
        startBackendService()
        requestNotificationPermissionIfNeeded()
        requestOverlayPermissionIfNeeded()

        setProgress(10, "Starting Theia backend…", "Extracting runtime assets")
        startHealthCheck()
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 2297)
        }
    }

    private fun requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = android.net.Uri.parse(String.format("package:%s", packageName))
                    startActivityForResult(intent, 2296)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, 2296)
                }
            }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (ContextCompat.checkSelfPermission(this, permissions[0]) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, 100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!backendLoaded) {
            startHealthCheck()
        }
    }

    override fun onPause() {
        super.onPause()
        healthCheckTimer?.cancel()
        healthCheckTimer = null
    }

    override fun onDestroy() {
        healthCheckTimer?.cancel()
        super.onDestroy()
    }

    private fun startBackendService() {
        val intent = Intent(this, TheiaBackendService::class.java).apply {
            action = TheiaBackendService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun configureWebView() {
        webView.webViewClient = LocalhostOnlyWebViewClient(this)
        webView.webChromeClient = WebChromeClient()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            databaseEnabled = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            safeBrowsingEnabled = true
        }

        if (com.theia.mobile.BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun startHealthCheck() {
        if (healthCheckTimer != null) return

        val startTime = System.currentTimeMillis()
        healthCheckTimer = Timer("health-check", true).apply {
            scheduleAtFixedRate(0L, HEALTH_CHECK_INTERVAL_MS) {
                val port = resolveBackendPort()
                if (port <= 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = 10 + ((elapsed.toFloat() / HEALTH_CHECK_TIMEOUT_MS) * 70).toInt().coerceAtMost(70)
                    runOnUiThread { setProgress(progress, "Starting Theia backend…", "Waiting for backend process") }

                    if (elapsed > HEALTH_CHECK_TIMEOUT_MS) {
                        runOnUiThread {
                            setProgress(100, "Backend not reachable",
                                "Timed out. Check logs in filesDir/logs/")
                        }
                        cancel()
                    }
                    return@scheduleAtFixedRate
                }

                // Backend port detected — check if HTTP is ready
                val ready = try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                        true
                    }
                } catch (_: Exception) {
                    false
                }

                if (ready) {
                    runOnUiThread { loadIde(port) }
                    cancel()
                } else {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = 40 + ((elapsed.toFloat() / HEALTH_CHECK_TIMEOUT_MS) * 50).toInt().coerceAtMost(50)
                    runOnUiThread { setProgress(progress, "Backend starting…", "Waiting for HTTP response on port $port") }
                }
            }
        }
    }

    private fun loadIde(port: Int) {
        if (backendLoaded) return
        backendLoaded = true
        healthCheckTimer?.cancel()
        healthCheckTimer = null

        val url = "http://127.0.0.1:$port/"
        setProgress(100, "Ready", "Loading Theia IDE…")
        Log.i(TAG, "Loading IDE URL: $url")

        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { loadingOverlay.visibility = View.GONE }
            .start()

        webView.loadUrl(url)
    }

    private fun setProgress(value: Int, status: String, detail: String) {
        progressBar.progress = value
        statusText.text = status
        detailText.text = detail
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1101
                )
            }
        }
    }

    private fun resolveBackendPort(): Int {
        val active = TheiaBackendService.activePort
        if (active > 0) return active

        val statusFile = File(filesDir, "backend-status.json")
        if (!statusFile.exists()) return -1

        return try {
            val json = statusFile.readText()
            val marker = json.indexOf("\"port\":")
            if (marker < 0) return -1
            var start = marker + 7
            while (start < json.length && !json[start].isDigit()) start++
            var end = start
            while (end < json.length && json[end].isDigit()) end++
            if (end > start) json.substring(start, end).toInt() else -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse backend status file", e)
            -1
        }
    }
}
