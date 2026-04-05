package com.theia.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-screen preparation flow.
 *
 * The app auto-installs the embedded Termux runtime, installs Debian with official
 * proot-distro, starts the host backend, waits for HTTP readiness, and opens the IDE.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnboardingActivity"
        private const val DEFAULT_USERNAME = "root"
        private const val BACKEND_POLL_INTERVAL_MS = 1200L
        private const val BACKEND_TIMEOUT_MS = 180_000L
    }

    private lateinit var stateManager: OnboardingStateManager
    private lateinit var logoView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var retryButton: Button

    @Volatile
    private var installStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        stateManager = OnboardingStateManager(this)
        logoView = findViewById(R.id.onboarding_logo)
        progressBar = findViewById(R.id.progress_install)
        statusText = findViewById(R.id.tv_install_status)
        detailText = findViewById(R.id.tv_install_detail)
        retryButton = findViewById(R.id.btn_retry_install)

        configureLogoView()
        retryButton.setOnClickListener {
            if (!installStarted) {
                stateManager.setInstallError(null)
                retryButton.visibility = View.GONE
                startPreparationFlow()
            }
        }

        val config = stateManager.getConfig()
        if (config.state == OnboardingStateManager.OnboardingState.READY_TO_LAUNCH_IDE ||
            config.state == OnboardingStateManager.OnboardingState.IDE_RUNNING
        ) {
            Log.i(TAG, "Onboarding already complete, launching main activity")
            launchMainActivity()
            return
        }

        seedDefaultInstallConfig()
        startPreparationFlow()
    }

    private fun configureLogoView() {
        logoView.setBackgroundColor(Color.TRANSPARENT)
        logoView.isVerticalScrollBarEnabled = false
        logoView.isHorizontalScrollBarEnabled = false
        logoView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
        }
        val html = """
            <html>
              <body style="margin:0;background:transparent;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden;">
                <img src="logo2.svg" style="width:100%;height:100%;object-fit:contain;" alt="DevPocket logo" />
              </body>
            </html>
        """.trimIndent()
        logoView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    }

    private fun seedDefaultInstallConfig() {
        val config = stateManager.getConfig()
        if (config.username.isNullOrBlank()) {
            Log.i(TAG, "Seeding fixed Debian username: $DEFAULT_USERNAME")
            stateManager.setUsername(DEFAULT_USERNAME)
        }
        if (config.sudoMode != UserAccountConfig.SUDO_MODE_PASSWORDLESS) {
            stateManager.setSudoMode(UserAccountConfig.SUDO_MODE_PASSWORDLESS)
        }
        stateManager.setSudoPassword(null)
    }

    private fun startPreparationFlow() {
        if (installStarted) {
            return
        }
        installStarted = true
        stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
        setProgress(5, "Preparing DevPocket", "Installing Termux runtime, Debian, and the IDE backend")
        Log.i(TAG, "Starting automatic onboarding flow")

        Thread {
            val installer = BootstrapInstallerService(this)
            installer.setProgressCallback { progress ->
                runOnUiThread {
                    updateInstallProgress(progress)
                }
            }

            val installSuccess = installer.install()
            installer.cleanup()

            if (!installSuccess) {
                val message = stateManager.getConfig().installError ?: "Failed to install Debian runtime"
                runOnUiThread {
                    showFailure("Setup failed", message)
                }
                return@Thread
            }

            Log.i(TAG, "Debian installation completed, starting backend readiness wait")
            runOnUiThread {
                setProgress(82, "Starting DevPocket", "Connecting embedded backend")
            }
            waitForBackendAndOpenIde()
        }.start()
    }

    private fun updateInstallProgress(progress: BootstrapInstallerService.InstallProgress) {
        val mappedProgress = when (progress.phase) {
            "bootstrap-downloading" -> 5 + (progress.percentComplete * 20 / 100)
            "bootstrap-verifying" -> 26 + (progress.percentComplete * 4 / 100)
            "bootstrap-extracting" -> 30 + (progress.percentComplete * 15 / 100)
            "termux-upgrading" -> 45 + (progress.percentComplete * 10 / 100)
            "termux-installing-packages" -> 55 + (progress.percentComplete * 18 / 100)
            "debian-installing" -> 73 + (progress.percentComplete * 17 / 100)
            else -> progress.percentComplete.coerceIn(5, 85)
        }.coerceIn(5, 85)

        val status = when (progress.phase) {
            "bootstrap-downloading" -> "Downloading Termux runtime"
            "bootstrap-verifying" -> "Verifying Termux runtime"
            "bootstrap-extracting" -> "Extracting Termux runtime"
            "termux-upgrading" -> "Upgrading Termux packages"
            "termux-installing-packages" -> "Installing nodejs, proot, and proot-distro"
            "debian-installing" -> "Installing Debian with proot-distro"
            else -> "Preparing DevPocket"
        }
        val detail = when (progress.phase) {
            "bootstrap-downloading" -> "Fetching official Termux bootstrap (${progress.percentComplete}%)"
            "bootstrap-verifying" -> "Checking bootstrap integrity"
            "bootstrap-extracting" -> "Preparing the embedded Termux prefix (${progress.percentComplete}%)"
            "termux-upgrading" -> "Running pkg update and pkg upgrade"
            "termux-installing-packages" -> "Installing the host runtime packages used by DevPocket"
            "debian-installing" -> "Downloading and provisioning Debian inside proot-distro"
            else -> "Installing runtime components"
        }

        setProgress(mappedProgress, status, detail)
    }

    private fun waitForBackendAndOpenIde() {
        startBackendService()
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < BACKEND_TIMEOUT_MS) {
            val port = resolveBackendPort()
            if (port > 0 && isBackendReady(port)) {
                Log.i(TAG, "Backend became ready on port $port during onboarding")
                runOnUiThread {
                    setProgress(100, "Opening DevPocket", "Launching the IDE")
                    launchMainActivity()
                }
                return
            }

            val elapsed = System.currentTimeMillis() - startedAt
            val progress = (82 + ((elapsed.toFloat() / BACKEND_TIMEOUT_MS) * 17).toInt()).coerceAtMost(99)
            runOnUiThread {
                if (port > 0) {
                    setProgress(progress, "Connecting backend", "Waiting for HTTP response on port $port")
                } else {
                    setProgress(progress, "Starting backend", "Waiting for backend process")
                }
            }

            try {
                Thread.sleep(BACKEND_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        runOnUiThread {
            showFailure("Backend startup failed", "The backend did not become ready on localhost in time")
        }
    }

    private fun startBackendService() {
        Log.i(TAG, "Starting Theia backend service from onboarding")
        val intent = Intent(this, TheiaBackendService::class.java).apply {
            action = TheiaBackendService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isBackendReady(port: Int): Boolean {
        return try {
            val connection = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 700
                readTimeout = 700
            }
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == 200 || responseCode == 301 || responseCode == 302 || responseCode == 404
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveBackendPort(): Int {
        val active = TheiaBackendService.activePort
        if (active > 0) {
            return active
        }

        val statusFile = File(filesDir, "backend-status.json")
        if (!statusFile.exists()) {
            return -1
        }

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
            Log.w(TAG, "Failed to parse backend status file during onboarding", e)
            -1
        }
    }

    private fun setProgress(progress: Int, status: String, detail: String) {
        progressBar.progress = progress.coerceIn(0, 100)
        statusText.text = status
        detailText.text = detail
    }

    private fun showFailure(status: String, detail: String) {
        Log.e(TAG, "$status: $detail")
        installStarted = false
        stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED)
        stateManager.setInstallError(detail)
        setProgress(100, status, detail)
        retryButton.visibility = View.VISIBLE
    }

    private fun launchMainActivity() {
        stateManager.setState(OnboardingStateManager.OnboardingState.IDE_RUNNING)
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d(TAG, "Back press blocked during automatic onboarding")
    }
}
