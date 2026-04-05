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

    private data class BackendLaunchContext(
        val hostWorkspace: File,
        val hostExtensionsDir: File,
        val hostNode: File,
        val hostEntrypoint: File,
        val hostRuntimeRoot: File,
        val hostConfigDir: File,
        val hostOvsxConfig: File,
        val guestWorkspace: String,
        val guestExtensionsDir: String,
        val guestNode: String,
        val guestEntrypoint: String,
        val guestProjectRoot: String,
        val guestConfigDir: String,
        val guestRuntimeBin: String,
        val guestRuntimeLib: String,
        val guestOvsxConfig: String,
        val guestCaCertPath: String,
    )

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
        val launchContext = prepareBackendLaunchContext()
        val onboardingManager = OnboardingStateManager(this)
        val onboardingConfig = onboardingManager.getConfig()
        val debianReady = onboardingManager.isOnboardingComplete() &&
            !onboardingConfig.username.isNullOrBlank() &&
            TheiaBackendConfig.validateDebianSetup(this)

        return if (debianReady) {
            val username = onboardingConfig.username ?: "devpocket"
            logLine("Launching backend inside Debian as $username")
            createDebianProcessBuilder(port, username, launchContext)
        } else {
            logLine("Launching backend in host runtime")
            createHostProcessBuilder(port, launchContext)
        }
    }

    @Throws(IOException::class)
    private fun prepareBackendLaunchContext(): BackendLaunchContext {
        val hostNode = TheiaRuntimePaths.nodeBinary(this)
        val hostEntrypoint = TheiaRuntimePaths.backendEntrypoint(this)
        if (!hostNode.exists()) {
            throw IOException("Missing node binary at ${hostNode.absolutePath}")
        }
        if (!hostEntrypoint.exists()) {
            throw IOException("Missing backend entrypoint at ${hostEntrypoint.absolutePath}")
        }

        val hostWorkspace = TheiaRuntimePaths.getIdeWorkspace(this)
        if (!hostWorkspace.exists()) {
            hostWorkspace.mkdirs()
        }

        val hostConfigDir = TheiaRuntimePaths.configDir(this)
        hostConfigDir.mkdirs()

        val hostExtensionsDir = ensureBundledExtensionsExtracted()

        logLine("IDE workspace resolved to: ${hostWorkspace.absolutePath}")

        return BackendLaunchContext(
            hostWorkspace = hostWorkspace,
            hostExtensionsDir = hostExtensionsDir,
            hostNode = hostNode,
            hostEntrypoint = hostEntrypoint,
            hostRuntimeRoot = TheiaRuntimePaths.runtimeRoot(this),
            hostConfigDir = hostConfigDir,
            hostOvsxConfig = TheiaRuntimePaths.ovsxRouterConfig(this),
            guestWorkspace = TheiaRuntimePaths.debianGuestWorkspace(this),
            guestExtensionsDir = TheiaRuntimePaths.debianGuestExtensionsDir(),
            guestNode = TheiaRuntimePaths.debianGuestNodeBinary(),
            guestEntrypoint = TheiaRuntimePaths.debianGuestBackendEntrypoint(),
            guestProjectRoot = TheiaRuntimePaths.debianGuestProjectRoot(),
            guestConfigDir = TheiaRuntimePaths.debianGuestConfigDir(),
            guestRuntimeBin = TheiaRuntimePaths.debianGuestRuntimeBin(),
            guestRuntimeLib = TheiaRuntimePaths.debianGuestRuntimeLib(),
            guestOvsxConfig = TheiaRuntimePaths.debianGuestOvsxRouterConfig(),
            guestCaCertPath = TheiaRuntimePaths.debianGuestCaCertPath(),
        )
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
                        Log.i(TAG, "Extracting bundled extension: ${vsix.name} → ${destDir.absolutePath}")
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

    private fun createHostProcessBuilder(port: Int, launchContext: BackendLaunchContext): ProcessBuilder {
        val command = mutableListOf(
            launchContext.hostNode.absolutePath,
            launchContext.hostEntrypoint.absolutePath,
            launchContext.hostWorkspace.absolutePath,
            "--hostname", "127.0.0.1",
            "--port", port.toString(),
            "--plugins=local-dir:${launchContext.hostExtensionsDir.absolutePath}"
        )
        if (launchContext.hostOvsxConfig.exists()) {
            command.add("--ovsx-router-config=${launchContext.hostOvsxConfig.absolutePath}")
        }

        val builder = ProcessBuilder(command)
        builder.directory(launchContext.hostRuntimeRoot)

        val env = builder.environment()
        val runtimeBin = File(launchContext.hostRuntimeRoot, "bin").absolutePath
        val runtimeLib = File(launchContext.hostRuntimeRoot, "lib").absolutePath
        val existingPath = env.getOrDefault("PATH", "")
        env["PATH"] = "$runtimeBin:$existingPath"
        env["LD_LIBRARY_PATH"] = runtimeLib
        env["HOME"] = launchContext.hostWorkspace.absolutePath

        val onboardingManager = OnboardingStateManager(this)
        val onboardingConfig = onboardingManager.getConfig()
        if (!onboardingConfig.username.isNullOrBlank()) {
            val debianRoot = File(filesDir, "linux/debian")
            if (debianRoot.exists()) {
                env["DEVPOCKET_DEBIAN_ROOT"] = debianRoot.absolutePath
                env["DEVPOCKET_USER"] = onboardingConfig.username
                env["DEVPOCKET_WORKSPACE"] = launchContext.hostWorkspace.absolutePath
            }
        }

        env["THEIA_DEFAULT_PLUGINS"] = "local-dir:${launchContext.hostExtensionsDir.absolutePath}"
        env["THEIA_PLUGINS"] = "local-dir:${launchContext.hostExtensionsDir.absolutePath}"
        env.putAll(TheiaBackendConfig.getBackendEnvironmentVariables(this))
        env["THEIA_ANDROID_LITE"] = "1"
        env["THEIA_ANDROID_LITE_HOME"] = filesDir.absolutePath
        env["THEIA_CONFIG_DIR"] = launchContext.hostConfigDir.absolutePath
        env["THEIA_EXTENSIONS_DIR"] = launchContext.hostExtensionsDir.absolutePath
        env["THEIA_ANDROID_RUNTIME_BIN"] = runtimeBin
        env["THEIA_ANDROID_RUNTIME_LIB"] = runtimeLib
        env["THEIA_APP_PROJECT_PATH"] = File(launchContext.hostRuntimeRoot, "theia-android-lite").absolutePath
        env["OPENSSL_CONF"] = "/dev/null"

        val termuxBash = File(runtimeBin, "bash")
        val termuxSh = File(runtimeBin, "sh")
        val resolvedShell = if (termuxBash.exists()) termuxBash.absolutePath else if (termuxSh.exists()) termuxSh.absolutePath else "/system/bin/sh"
        val effectiveShell = env["THEIA_SHELL"]?.takeIf { it.isNotBlank() } ?: resolvedShell
        env["SHELL"] = effectiveShell
        env["THEIA_SHELL"] = effectiveShell
        env["npm_config_script_shell"] = resolvedShell
        env["npm_config_shell"] = resolvedShell
        env["THEIA_WEBVIEW_EXTERNAL_ENDPOINT"] = "{{hostname}}"
        env["CHOKIDAR_USEPOLLING"] = "1"
        env["CHOKIDAR_INTERVAL"] = "1000"
        env["THEIA_DISABLE_TRASH"] = "true"

        val gitExecPath = File(File(launchContext.hostRuntimeRoot, "bin"), "libexec/git-core").absolutePath
        env["GIT_EXEC_PATH"] = gitExecPath
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_TEMPLATE_DIR"] = ""

        val caCertPath = File(launchContext.hostRuntimeRoot, "etc/ca-certificates/cacert.pem")
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

    private fun createDebianProcessBuilder(port: Int, username: String, launchContext: BackendLaunchContext): ProcessBuilder {
        val debianRoot = TheiaRuntimePaths.getDebianRoot(this)
        BootstrapInstallerService(this).copyShellWrapperToDebianBin(debianRoot)

        val wrapper = File(debianRoot, "bin/devpocket-shell")
        if (!wrapper.exists()) {
            throw IOException("Missing Debian shell wrapper at ${wrapper.absolutePath}")
        }

        val launchCommand = mutableListOf(
            launchContext.guestNode,
            launchContext.guestEntrypoint,
            launchContext.guestWorkspace,
            "--hostname", "127.0.0.1",
            "--port", port.toString(),
            "--plugins=local-dir:${launchContext.guestExtensionsDir}"
        )
        if (launchContext.hostOvsxConfig.exists()) {
            launchCommand.add("--ovsx-router-config=${launchContext.guestOvsxConfig}")
        }

        val backendEnv = linkedMapOf(
            "DEVPOCKET_BACKEND_IN_DEBIAN" to "1",
            "DEVPOCKET_DEBIAN_ROOT" to "/",
            "DEVPOCKET_USER" to username,
            "DEVPOCKET_WORKSPACE" to launchContext.guestWorkspace,
            "THEIA_DEFAULT_PLUGINS" to "local-dir:${launchContext.guestExtensionsDir}",
            "THEIA_PLUGINS" to "local-dir:${launchContext.guestExtensionsDir}",
            "THEIA_ANDROID_LITE" to "1",
            "THEIA_CONFIG_DIR" to launchContext.guestConfigDir,
            "THEIA_EXTENSIONS_DIR" to launchContext.guestExtensionsDir,
            "THEIA_ANDROID_RUNTIME_BIN" to launchContext.guestRuntimeBin,
            "THEIA_ANDROID_RUNTIME_LIB" to launchContext.guestRuntimeLib,
            "THEIA_APP_PROJECT_PATH" to launchContext.guestProjectRoot,
            "THEIA_WEBVIEW_EXTERNAL_ENDPOINT" to "{{hostname}}",
            "THEIA_DISABLE_TRASH" to "true",
            "CHOKIDAR_USEPOLLING" to "1",
            "CHOKIDAR_INTERVAL" to "1000",
            "OPENSSL_CONF" to "/dev/null",
            "SHELL" to "/bin/bash",
            "THEIA_SHELL" to "/bin/bash",
            "npm_config_script_shell" to "/bin/bash",
            "npm_config_shell" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${launchContext.guestRuntimeBin}",
            "LD_LIBRARY_PATH" to launchContext.guestRuntimeLib,
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
        )
        val guestCaCert = File(launchContext.hostRuntimeRoot, "etc/ca-certificates/cacert.pem")
        if (guestCaCert.exists()) {
            backendEnv["SSL_CERT_FILE"] = launchContext.guestCaCertPath
            backendEnv["NODE_EXTRA_CA_CERTS"] = launchContext.guestCaCertPath
            backendEnv["CURL_CA_BUNDLE"] = launchContext.guestCaCertPath
        }

        val shellScript = buildShellScript(backendEnv, launchCommand)
        val builder = ProcessBuilder(listOf(wrapper.absolutePath, "-c", shellScript))
        builder.directory(filesDir)
        return builder
    }

    private fun buildShellScript(environment: Map<String, String>, command: List<String>): String {
        val script = StringBuilder()
        environment.forEach { (key, value) ->
            script.append("export ")
                .append(key)
                .append('=')
                .append(shellQuote(value))
                .append('\n')
        }
        script.append("exec ")
            .append(command.joinToString(" ") { shellQuote(it) })
        return script.toString()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

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
