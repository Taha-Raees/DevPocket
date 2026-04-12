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

/**
 * Theia backend runs in the embedded Termux-like host runtime.
 * Terminal sessions, shell tasks, and build commands all target Debian via devpocket-shell.
 */
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
            } else {
                logLine("Backend health check passed on port $selectedPort")
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
        val installer = BootstrapInstallerService(this)
        if (!installer.ensureRuntimeCompatibility()) {
            logLine("Runtime compatibility refresh failed; continuing with existing shell/runtime files")
        }

        val termuxPrefix = TheiaRuntimePaths.termuxPrefix(this)
        val termuxHome = TheiaRuntimePaths.termuxHome(this)
        val termuxTmp = TheiaRuntimePaths.termuxTmp(this)
        val termuxBinDir = TheiaRuntimePaths.termuxBin(this)
        val termuxLibDir = TheiaRuntimePaths.termuxLib(this)
        val termuxShellWrapper = TheiaRuntimePaths.termuxShellWrapper(this)
        val termuxEnvWrapper = TheiaRuntimePaths.termuxEnvWrapper(this)
        val node = TheiaRuntimePaths.nodeBinary(this)
        val entry = TheiaRuntimePaths.backendEntrypoint(this)
        if (!node.exists()) {
            throw IOException("Missing node binary at ${node.absolutePath}")
        }
        if (!entry.exists()) {
            throw IOException("Missing backend entrypoint at ${entry.absolutePath}")
        }

        val workspace = TheiaRuntimePaths.getIdeWorkspace(this)
        termuxHome.mkdirs()
        termuxTmp.mkdirs()

        val configDir = TheiaRuntimePaths.configDir(this)
        configDir.mkdirs()

        val extensionsDir = ensureBundledExtensionsExtracted()

        logLine("Debian home resolved to: ${workspace.absolutePath}")
        logLine("Launching backend without a default workspace argument")

        val runtimeRoot = TheiaRuntimePaths.runtimeRoot(this)
        val runtimeBin = File(runtimeRoot, "bin").absolutePath
        val runtimeLib = File(runtimeRoot, "lib").absolutePath
        val termuxBin = termuxBinDir.absolutePath
        val termuxLib = termuxLibDir.absolutePath

        val command = mutableListOf(
            termuxEnvWrapper.absolutePath,
            node.absolutePath,
            entry.absolutePath,
            "--hostname", "127.0.0.1",
            "--port", port.toString(),
            "--plugins=local-dir:${extensionsDir.absolutePath}"
        )

        val ovsxConfig = TheiaRuntimePaths.ovsxRouterConfig(this)
        if (ovsxConfig.exists()) {
            command.add("--ovsx-router-config=${ovsxConfig.absolutePath}")
        }

        val builder = ProcessBuilder(command)
        builder.directory(termuxHome)

        val env = builder.environment()
        val existingPath = env.getOrDefault("PATH", "")
        env["PATH"] = "$termuxBin:$runtimeBin:$existingPath"
        env["LD_LIBRARY_PATH"] = "$termuxLib:$runtimeLib"
        env["PREFIX"] = termuxPrefix.absolutePath
        env["HOME"] = termuxHome.absolutePath
        env["TMPDIR"] = termuxTmp.absolutePath

        val onboardingManager = OnboardingStateManager(this)
        val onboardingConfig = onboardingManager.getConfig()
        if (!onboardingConfig.username.isNullOrBlank()) {
            val debianRoot = TheiaRuntimePaths.getDebianRoot(this)
            if (debianRoot.exists()) {
                env["DEVPOCKET_DEBIAN_ROOT"] = debianRoot.absolutePath
                env["DEVPOCKET_USER"] = onboardingConfig.username
                env["DEVPOCKET_WORKSPACE"] = workspace.absolutePath
            }
        }

        env["THEIA_DEFAULT_PLUGINS"] = "local-dir:${extensionsDir.absolutePath}"
        env["THEIA_PLUGINS"] = "local-dir:${extensionsDir.absolutePath}"
        env.putAll(TheiaBackendConfig.getBackendEnvironmentVariables(this))
        env["THEIA_ANDROID_LITE"] = "1"
        env["THEIA_ANDROID_LITE_HOME"] = filesDir.absolutePath
        env["THEIA_CONFIG_DIR"] = configDir.absolutePath
        env["THEIA_EXTENSIONS_DIR"] = extensionsDir.absolutePath
        env["THEIA_ANDROID_RUNTIME_BIN"] = runtimeBin
        env["THEIA_ANDROID_RUNTIME_LIB"] = runtimeLib
        env["THEIA_APP_PROJECT_PATH"] = File(runtimeRoot, "theia-android-lite").absolutePath
        env["OPENSSL_CONF"] = "/dev/null"

        val effectiveShell = env["THEIA_SHELL"]?.takeIf { it.isNotBlank() } ?: termuxShellWrapper.absolutePath
        env["SHELL"] = effectiveShell
        env["THEIA_SHELL"] = effectiveShell
        env["npm_config_script_shell"] = effectiveShell
        env["npm_config_shell"] = effectiveShell
        env["THEIA_WEBVIEW_EXTERNAL_ENDPOINT"] = "{{hostname}}"
        env["CHOKIDAR_USEPOLLING"] = "1"
        env["CHOKIDAR_INTERVAL"] = "1000"
        env["THEIA_DISABLE_TRASH"] = "true"

        val gitExecPath = File(termuxPrefix, "libexec/git-core")
        if (gitExecPath.exists()) {
            env["GIT_EXEC_PATH"] = gitExecPath.absolutePath
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["GIT_TEMPLATE_DIR"] = ""
        }

        val termuxCaCert = File(termuxPrefix, "etc/tls/cert.pem")
        val runtimeCaCert = File(runtimeRoot, "etc/ca-certificates/cacert.pem")
        val caCertPath = if (termuxCaCert.exists()) termuxCaCert else runtimeCaCert
        if (caCertPath.exists()) {
            env["SSL_CERT_FILE"] = caCertPath.absolutePath
            env["GIT_SSL_CAINFO"] = caCertPath.absolutePath
            env["NODE_EXTRA_CA_CERTS"] = caCertPath.absolutePath
            env["CURL_CA_BUNDLE"] = caCertPath.absolutePath
        }

        env["npm_config_bin_links"] = "false"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        return builder
    }

    private fun ensureBundledExtensionsExtracted(): File {
        val bundledExtDir = File(TheiaRuntimePaths.runtimeRoot(this), "extensions")
        val userExtDir = TheiaRuntimePaths.extensionsRoot(this)
        if (bundledExtDir.exists() && bundledExtDir.isDirectory) {
            userExtDir.mkdirs()
            bundledExtDir.listFiles()?.filter { it.name.endsWith(".vsix") }?.forEach { vsix ->
                val extName = vsix.nameWithoutExtension
                val destDir = File(userExtDir, extName)
                if (!destDir.exists()) {
                    try {
                        Log.i(TAG, "Extracting bundled extension: ${vsix.name} -> ${destDir.absolutePath}")
                        destDir.mkdirs()
                        val zipIn = java.util.zip.ZipInputStream(vsix.inputStream().buffered())
                        var entry = zipIn.nextEntry
                        while (entry != null) {
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
        return userExtDir
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
