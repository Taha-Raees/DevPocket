package com.theia.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TheiaBackendService : Service() {

    companion object {
        private const val TAG = "TheiaBackendService"
        private const val CHANNEL_ID = "theia_backend_channel"
        private const val NOTIFICATION_ID = 3001

        private const val DEFAULT_PORT = 3100
        private const val PORT_RANGE_START = 3100
        private const val PORT_RANGE_END = 3199

        const val ACTION_START = "com.theia.mobile.action.START_BACKEND"
        const val ACTION_STOP = "com.theia.mobile.action.STOP_BACKEND"

        @Volatile
        var activePort: Int = -1
            private set

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val stopping = AtomicBoolean(false)
    private var supervisorThread: Thread? = null
    private var backendProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var fileLogger: RotatingFileLogger

    override fun onCreate() {
        super.onCreate()
        fileLogger = RotatingFileLogger(this, "theia-backend")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (ACTION_STOP == action) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting backend..."))
        if (supervisorThread == null || supervisorThread?.isAlive != true) {
            stopping.set(false)
            supervisorThread = Thread({ runBackendSupervisor() }, "theia-backend-supervisor").also {
                it.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSelfSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runBackendSupervisor() {
        var selectedPort = DEFAULT_PORT
        try {
            // Acquire WakeLock to keep CPU alive while backend runs
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DevPocket::Backend").apply {
                acquire()
            }
            AssetExtractor.ensureExtracted(this)
            selectedPort = PortAllocator.findAvailablePort(PORT_RANGE_START, PORT_RANGE_END + 1)
            activePort = selectedPort
            writeStatusFile(selectedPort)
            updateNotification("Backend on 127.0.0.1:$selectedPort")

            val builder = createProcessBuilder(selectedPort)
            backendProcess = builder.start()
            isRunning = true

            val outThread = streamToLog("stdout", backendProcess!!.inputStream)
            val errThread = streamToLog("stderr", backendProcess!!.errorStream)

            val healthy = PortAllocator.waitForHttpReady("127.0.0.1", selectedPort, 60_000L)
            if (!healthy) {
                logLine("Backend health check timed out on port $selectedPort")
            }

            val exit = backendProcess!!.waitFor()
            outThread.join(2_000L)
            errThread.join(2_000L)
            isRunning = false
            logLine("Backend process exited with code $exit")
        } catch (e: Exception) {
            isRunning = false
            logLine("Backend supervisor failed: ${e.message}")
            Log.e(TAG, "Backend supervisor failure", e)
        } finally {
            backendProcess = null
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            if (!stopping.get()) {
                updateNotification("Backend stopped")
            }
        }
    }

    @Throws(IOException::class)
    private fun createProcessBuilder(port: Int): ProcessBuilder {
        val node = TheiaRuntimePaths.nodeBinary(this)
        val entry = TheiaRuntimePaths.backendEntrypoint(this)
        if (!node.exists()) {
            throw IOException("Missing node binary at ${node.absolutePath}")
        }
        if (!entry.exists()) {
            throw IOException("Missing backend entrypoint at ${entry.absolutePath}")
        }

        // Resolve IDE workspace location:
        // 1. Primary: app-private Debian home (new model)
        // 2. Fallback: external shared storage (legacy)
        val devPocketWorkspace = TheiaRuntimePaths.getIdeWorkspace(this)
        if (!devPocketWorkspace.exists()) {
            devPocketWorkspace.mkdirs()
        }
        
        logLine("IDE workspace resolved to: ${devPocketWorkspace.absolutePath}")

        // Ensure bundled extensions from APK assets are extracted (unpacked) in the extensions directory.
        // Theia treats --plugins / THEIA_DEFAULT_PLUGINS as system plugins and system plugins
        // must be pre-unpacked directories — .vsix files are only auto-extracted for user plugins.
        val bundledExtDir = File(TheiaRuntimePaths.runtimeRoot(this), "extensions")
        val userExtDir = TheiaRuntimePaths.extensionsRoot(this)
        if (bundledExtDir.exists() && bundledExtDir.isDirectory) {
            userExtDir.mkdirs()
            bundledExtDir.listFiles()?.filter { it.name.endsWith(".vsix") }?.forEach { vsix ->
                // Extract the vsix (ZIP) into a named directory so Theia finds an unpacked extension
                val extName = vsix.nameWithoutExtension
                val destDir = File(userExtDir, extName)
                if (!destDir.exists()) {
                    try {
                        Log.i(TAG, "Extracting bundled extension: ${vsix.name} → ${destDir.absolutePath}")
                        destDir.mkdirs()
                        val zipIn = java.util.zip.ZipInputStream(vsix.inputStream().buffered())
                        var entry = zipIn.nextEntry
                        while (entry != null) {
                            // vsix files have entries under "extension/" prefix
                            val outFile = File(destDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().buffered().use { out ->
                                    zipIn.copyTo(out)
                                }
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                        zipIn.close()
                        Log.i(TAG, "Extracted extension: ${vsix.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to extract extension: ${vsix.name}", e)
                        destDir.deleteRecursively()
                    }
                }
            }
        }

        val command = mutableListOf(
            node.absolutePath,
            entry.absolutePath,
            devPocketWorkspace.absolutePath,
            "--hostname", "127.0.0.1",
            "--port", port.toString(),
            "--plugins=local-dir:${userExtDir.absolutePath}"
        )

        // Add OVSX router config if present
        val ovsxConfig = TheiaRuntimePaths.ovsxRouterConfig(this)
        if (ovsxConfig.exists()) {
            command.add("--ovsx-router-config=${ovsxConfig.absolutePath}")
        }

        val builder = ProcessBuilder(command)
        builder.directory(TheiaRuntimePaths.runtimeRoot(this))

        val env = builder.environment()
        val runtimeBin = File(TheiaRuntimePaths.runtimeRoot(this), "bin").absolutePath
        val runtimeLib = File(TheiaRuntimePaths.runtimeRoot(this), "lib").absolutePath
        val existingPath = env.getOrDefault("PATH", "")
        env["PATH"] = "$runtimeBin:$existingPath"
        env["LD_LIBRARY_PATH"] = runtimeLib
        env["HOME"] = devPocketWorkspace.absolutePath
        
        // Debian environment (available if onboarding complete)
        val onboardingManager = OnboardingStateManager(this)
        val onboardingConfig = onboardingManager.getConfig()
        if (onboardingConfig.username != null) {
            val debianRoot = File(filesDir, "linux/debian")
            if (debianRoot.exists()) {
                env["DEVPOCKET_DEBIAN_ROOT"] = debianRoot.absolutePath
                env["DEVPOCKET_USER"] = onboardingConfig.username
                env["DEVPOCKET_WORKSPACE"] = devPocketWorkspace.absolutePath
            }
        }
        
        env["THEIA_DEFAULT_PLUGINS"] = "local-dir:${TheiaRuntimePaths.extensionsRoot(this).absolutePath}"
        env["THEIA_PLUGINS"] = "local-dir:${TheiaRuntimePaths.extensionsRoot(this).absolutePath}"
        env.putAll(TheiaBackendConfig.getBackendEnvironmentVariables(this))
        env["THEIA_ANDROID_LITE"] = "1"
        env["THEIA_ANDROID_LITE_HOME"] = filesDir.absolutePath
        env["THEIA_CONFIG_DIR"] = TheiaRuntimePaths.configDir(this).absolutePath
        env["THEIA_EXTENSIONS_DIR"] = TheiaRuntimePaths.extensionsRoot(this).absolutePath
        env["THEIA_ANDROID_RUNTIME_BIN"] = runtimeBin
        // Tell Theia where the project root is so it can find lib/frontend for static serving
        val appProjectPath = File(TheiaRuntimePaths.runtimeRoot(this), "theia-android-lite").absolutePath
        env["THEIA_APP_PROJECT_PATH"] = appProjectPath
        // Bypass Termux-hardcoded openssl.cnf permissions error
        env["OPENSSL_CONF"] = "/dev/null"
        // Force node-pty and execa children to use the Termux bash instead of /system/bin/sh
        val termuxBash = File(runtimeBin, "bash")
        val termuxSh = File(runtimeBin, "sh")
        val resolvedShell = if (termuxBash.exists()) termuxBash.absolutePath else if (termuxSh.exists()) termuxSh.absolutePath else "/system/bin/sh"
        val effectiveShell = env["THEIA_SHELL"]?.takeIf { it.isNotBlank() } ?: resolvedShell
        env["SHELL"] = effectiveShell
        env["THEIA_SHELL"] = effectiveShell
        env["npm_config_script_shell"] = resolvedShell
        env["npm_config_shell"] = resolvedShell
        // Fix webview rendering: use same-origin pattern instead of subdomain-based
        env["THEIA_WEBVIEW_EXTERNAL_ENDPOINT"] = "{{hostname}}"
        // Android strict inotify limits cause "Unable to watch for file changes"
        // Force chokidar to use polling instead of native OS events
        env["CHOKIDAR_USEPOLLING"] = "1"
        env["CHOKIDAR_INTERVAL"] = "1000"
        // Prevent "Cannot move to trash" errors since Android /storage/emulated/0 lacks a valid .Trash
        env["THEIA_DISABLE_TRASH"] = "true"

        // Git: set GIT_EXEC_PATH so git can find its helper commands (git-remote-https, etc.)
        val gitExecPath = File(File(TheiaRuntimePaths.runtimeRoot(this), "bin"), "libexec/git-core").absolutePath
        env["GIT_EXEC_PATH"] = gitExecPath

        // Git: prevent git from reading Termux's gitconfig which doesn't exist in our sandbox
        env["GIT_CONFIG_NOSYSTEM"] = "1"

        // Git: set a default template dir to prevent "warning: templates not found"
        env["GIT_TEMPLATE_DIR"] = ""

        // SSL: point OpenSSL and curl/git at our bundled CA certificate bundle
        val caCertPath = File(TheiaRuntimePaths.runtimeRoot(this), "etc/ca-certificates/cacert.pem").absolutePath
        if (File(caCertPath).exists()) {
            env["SSL_CERT_FILE"] = caCertPath
            env["GIT_SSL_CAINFO"] = caCertPath
            env["NODE_EXTRA_CA_CERTS"] = caCertPath
            env["CURL_CA_BUNDLE"] = caCertPath
        }

        // npm: disable symlinks (Android sandbox restricts symlink creation)
        env["npm_config_bin_links"] = "false"

        // Terminal: set TERM so programs detect terminal capabilities through the real PTY
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        return builder
    }

    private fun streamToLog(streamName: String, inputStream: java.io.InputStream): Thread {
        return Thread({
            try {
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logLine("[backend-$streamName] $line")
                        maybePersistDetectedPreviewPort(line!!)
                    }
                }
            } catch (e: IOException) {
                logLine("Failed reading backend $streamName: ${e.message}")
            }
        }, "theia-backend-$streamName").also { it.start() }
    }

    private fun stopSelfSafely() {
        if (!stopping.compareAndSet(false, true)) return
        isRunning = false

        backendProcess?.let { process ->
            process.destroy()
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        supervisorThread?.interrupt()
        supervisorThread = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.theia_backend_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.theia_backend_channel_description)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.theia_backend_notification_title))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun logLine(line: String) {
        Log.i(TAG, line)
        if (::fileLogger.isInitialized) {
            fileLogger.log(line)
        }
    }

    private fun writeStatusFile(port: Int) {
        val status = File(filesDir, "backend-status.json")
        val json = """{
  "port": $port,
  "host": "127.0.0.1",
  "startedAt": "${Instant.now()}"
}
"""
        try {
            status.writeText(json)
        } catch (e: IOException) {
            logLine("Failed to write backend status file: ${e.message}")
        }
    }

    private fun maybePersistDetectedPreviewPort(line: String) {
        val lower = line.lowercase()
        var idx = lower.indexOf("localhost:")
        if (idx < 0) {
            idx = lower.indexOf("127.0.0.1:")
        }
        if (idx < 0) return

        val colon = lower.indexOf(':', idx)
        if (colon < 0 || colon + 1 >= lower.length) return

        var end = colon + 1
        while (end < lower.length && lower[end].isDigit()) {
            end++
        }
        if (end <= colon + 1) return

        val port = try {
            lower.substring(colon + 1, end).toInt()
        } catch (_: NumberFormatException) {
            return
        }
        if (port <= 0 || port > 65535) return

        val preview = File(filesDir, "preview-status.json")
        val json = """{
  "host": "127.0.0.1",
  "port": $port,
  "detectedAt": "${Instant.now()}"
}
"""
        try {
            preview.writeText(json)
        } catch (e: IOException) {
            logLine("Failed to write preview status file: ${e.message}")
        }
    }
}
