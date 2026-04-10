package com.theia.mobile

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Installs an embedded Termux host runtime and then uses official `proot-distro`
 * to provision Debian. This replaces the old app-managed Debian rootfs flow.
 */
class BootstrapInstallerService(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapInstaller"
        private const val TERMUX_BOOTSTRAP_VERSION = "bootstrap-2026.04.05-r1+apt.android-7"
        private const val BOOTSTRAP_VERSION = "2.0.0-termux"
        private const val TERMUX_PACKAGES = "proot proot-distro nodejs"
        private const val DEBIAN_ALIAS = "debian"
        private const val ROOT_USERNAME = "root"
        private const val TEXT_PATCH_LIMIT_BYTES = 5L * 1024L * 1024L
        private const val LEGACY_TERMUX_FILES = "/data/data/com.termux/files"
        private const val LEGACY_TERMUX_CACHE = "/data/data/com.termux/cache"
        private const val LEGACY_TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val LEGACY_TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val LEGACY_TERMUX_TMP = "/data/data/com.termux/files/usr/tmp"
        private const val MAX_DIAGNOSTIC_LINES = 80

        private data class BootstrapArchive(
            val arch: String,
            val url: String,
            val sha256: String,
            val expectedBytes: Long,
        )

        private val bootstrapArchives = mapOf(
            "aarch64" to BootstrapArchive(
                arch = "aarch64",
                url = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.04.05-r1%2Bapt.android-7/bootstrap-aarch64.zip",
                sha256 = "5a454825f1aa0c6946c30cac5672c7402a79f7ffbdf97e8faabe1eaefce59058",
                expectedBytes = 30_763_662L,
            ),
            "arm" to BootstrapArchive(
                arch = "arm",
                url = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.04.05-r1%2Bapt.android-7/bootstrap-arm.zip",
                sha256 = "7327a9540ff82216c6ad3cf4603c9c1edffdd3f9bd78b2d2cf237092993e93db",
                expectedBytes = 27_655_674L,
            ),
            "x86_64" to BootstrapArchive(
                arch = "x86_64",
                url = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.04.05-r1%2Bapt.android-7/bootstrap-x86_64.zip",
                sha256 = "514133fa6c17fdf27081373e3492f90fd7d41fdff39f707ffa1181b2c600fdb9",
                expectedBytes = 30_610_083L,
            ),
        )
    }

    data class InstallProgress(
        val phase: String,
        val currentBytes: Long,
        val totalBytes: Long,
        val percentComplete: Int,
    )

    data class InstallManifest(
        val version: String = BOOTSTRAP_VERSION,
        val bootstrapVersion: String = TERMUX_BOOTSTRAP_VERSION,
        val installedAt: Long = System.currentTimeMillis(),
        val prefixPath: String = "",
        val debianPath: String = "",
        val hostPackages: String = TERMUX_PACKAGES,
        val extractedSuccessfully: Boolean = false,
    )

    private val linuxBase = File(context.filesDir, "linux")
    private val tempDir = File(linuxBase, "temp")
    private val manifestFile = File(linuxBase, "INSTALL_MANIFEST.json")

    private val termuxPrefix = TheiaRuntimePaths.termuxPrefix(context)
    private val termuxHome = TheiaRuntimePaths.termuxHome(context)
    private val termuxTmp = TheiaRuntimePaths.termuxTmp(context)
    private val termuxBin = TheiaRuntimePaths.termuxBin(context)
    private val termuxLib = TheiaRuntimePaths.termuxLib(context)
    private val termuxEnvWrapper = TheiaRuntimePaths.termuxEnvWrapper(context)
    private val termuxCompatWrapper = TheiaRuntimePaths.termuxCompatWrapper(context)
    private val termuxShellWrapper = TheiaRuntimePaths.termuxShellWrapper(context)
    private val runtimeRoot = TheiaRuntimePaths.runtimeRoot(context)
    private val runtimeProot = File(runtimeRoot, "bin/proot")
    private val termuxCompatCache = File(context.cacheDir, "termux-compat")
    private val debianRoot = TheiaRuntimePaths.getDebianRoot(context)

    private val stateManager = OnboardingStateManager(context)
    private var progressCallback: ((InstallProgress) -> Unit)? = null
    private val recentCommandOutput = ArrayDeque<String>()

    fun setProgressCallback(callback: (InstallProgress) -> Unit) {
        progressCallback = callback
    }

    fun install(): Boolean {
        try {
            Log.i(TAG, "Starting embedded Termux + proot-distro installation")
            recentCommandOutput.clear()
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
            seedDefaultAccountState()
            AssetExtractor.ensureExtracted(context)
            ensureBaseDirectories()

            ensureBootstrapInstalled()
            writeWrapperScripts()
            ensureHostPackagesInstalled()
            writeWrapperScripts()
            ensureDebianInstalled()
            finalizeDebianEnvironment()

            val manifest = InstallManifest(
                prefixPath = termuxPrefix.absolutePath,
                debianPath = debianRoot.absolutePath,
                extractedSuccessfully = true,
            )
            writeManifest(manifest)

            stateManager.setRootfsVersion(TERMUX_BOOTSTRAP_VERSION)
            stateManager.completeOnboarding()
            stateManager.setInstallProgress(100)
            Log.i(TAG, "Embedded Termux + Debian installation completed successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed: ${e.message}", e)
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED)
            stateManager.setInstallError(e.message ?: "Installation failed")
            return false
        }
    }

    fun repair(): Boolean {
        return try {
            Log.i(TAG, "Repairing embedded Termux runtime")
            ensureBaseDirectories()
            patchTextPrefixReferences()
            markExecutables(termuxPrefix)
            ensureTermuxPackageManagerDirectories()
            writeWrapperScripts()
            if (debianRoot.exists()) {
                finalizeDebianEnvironment()
            }
            stateManager.setRuntimeHealthy(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Repair failed: ${e.message}", e)
            stateManager.setInstallError(e.message ?: "Repair failed")
            false
        }
    }

    fun cleanup() {
        try {
            tempDir.deleteRecursively()
            Log.i(TAG, "Temporary bootstrap files cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed: ${e.message}")
        }
    }

    private fun ensureBaseDirectories() {
        linuxBase.mkdirs()
        tempDir.mkdirs()
        termuxHome.mkdirs()
        termuxTmp.mkdirs()
        termuxCompatCache.mkdirs()
        TheiaRuntimePaths.configDir(context).mkdirs()
        TheiaRuntimePaths.extensionsRoot(context).mkdirs()
    }

    private fun seedDefaultAccountState() {
        val config = stateManager.getConfig()
        if (config.username != ROOT_USERNAME) {
            stateManager.setUsername(ROOT_USERNAME)
        }
        if (config.sudoMode != UserAccountConfig.SUDO_MODE_PASSWORDLESS) {
            stateManager.setSudoMode(UserAccountConfig.SUDO_MODE_PASSWORDLESS)
        }
        stateManager.setSudoPassword(null)
    }

    private fun resolveBootstrapArchive(): BootstrapArchive {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val arch = when {
            abi.contains("arm64") -> "aarch64"
            abi.contains("armeabi") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            else -> throw IllegalStateException("Unsupported ABI for Termux bootstrap: $abi")
        }
        return bootstrapArchives[arch]
            ?: throw IllegalStateException("No bootstrap archive configured for architecture $arch")
    }

    private fun ensureBootstrapInstalled() {
        if (File(termuxBin, "bash").exists() && File(termuxBin, "pkg").exists()) {
            Log.i(TAG, "Termux bootstrap already present at ${termuxPrefix.absolutePath}")
            patchTextPrefixReferences()
            markExecutables(termuxPrefix)
            ensureTermuxPackageManagerDirectories()
            writeWrapperScripts()
            ensureBootstrapSecondStageCompleted()
            return
        }

        val archive = resolveBootstrapArchive()
        val archiveFile = File(tempDir, "bootstrap-${archive.arch}.zip")

        publishProgress("bootstrap-downloading", 0, archive.expectedBytes)
        downloadFile(archive.url, archiveFile)

        publishProgress("bootstrap-verifying", 0, archive.expectedBytes)
        val checksum = calculateSHA256(archiveFile)
        Log.i(TAG, "Bootstrap checksum: $checksum")
        if (checksum != archive.sha256) {
            archiveFile.delete()
            throw IllegalStateException("Bootstrap checksum mismatch for ${archive.arch}")
        }

        publishProgress("bootstrap-extracting", 0, 100)
        extractBootstrapZip(archiveFile, termuxPrefix)
        restoreBootstrapSymlinks()
        patchTextPrefixReferences()
        markExecutables(termuxPrefix)
        ensureTermuxPackageManagerDirectories()
        writeWrapperScripts()
        ensureBootstrapSecondStageCompleted()
    }

    private fun ensureBootstrapSecondStageCompleted() {
        val secondStageScript = File(
            termuxPrefix,
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
        )
        val secondStageLock = File(
            termuxPrefix,
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh.lock"
        )

        if (!secondStageScript.exists()) {
            Log.i(TAG, "No explicit Termux second-stage script found; skipping")
            return
        }
        if (secondStageLock.exists() || Files.isSymbolicLink(secondStageLock.toPath())) {
            Log.i(TAG, "Termux bootstrap second-stage already completed")
            return
        }

        val bash = File(termuxBin, "bash")
        Log.i(TAG, "Second-stage diagnostics: " +
            "bash=${bash.absolutePath} exists=${bash.exists()} exec=${bash.canExecute()} size=${bash.length()} " +
            "script=${secondStageScript.absolutePath} exists=${secondStageScript.exists()} size=${secondStageScript.length()} " +
            "proot=${runtimeProot.absolutePath} exists=${runtimeProot.exists()}")

        publishProgress("bootstrap-configuring", 0, 100)
        secondStageScript.setExecutable(true, false)

        // Primary approach: run via proot compat wrapper so dpkg's compiled-in legacy paths
        // (e.g. /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d) resolve through the
        // bind mount to the actual app-prefix location.
        try {
            runTermuxCompatCommand(
                listOf(bash.absolutePath, secondStageScript.absolutePath),
                "bootstrap-configuring",
                termuxDpkgEnv()
            )
            publishProgress("bootstrap-configuring", 100, 100)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Proot compat second-stage failed (${e.message}); trying direct fallback")
        }

        // Fallback: run directly via env wrapper (no proot). This works if the script's
        // text was patched to app-prefix paths, but dpkg binary calls may still fail
        // on hardcoded config paths.
        try {
            runTermuxCommand(
                listOf(bash.absolutePath, secondStageScript.absolutePath),
                "bootstrap-configuring-direct",
                termuxDpkgEnvLocal()
            )
            publishProgress("bootstrap-configuring", 100, 100)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Direct second-stage execution also failed (${e.message}); creating lock")
        }

        // Last resort: skip the second-stage and run dpkg --configure manually.
        Log.i(TAG, "Last resort: creating second-stage lock and running dpkg --configure -a")
        secondStageLock.parentFile?.mkdirs()
        secondStageLock.writeText("skipped-by-devpocket-fallback")
        try {
            runTermuxCompatCommand(
                listOf(bash.absolutePath, "-c",
                    "dpkg --configure -a --force-confnew --force-script-chrootless 2>/dev/null || true"),
                "bootstrap-dpkg-configure",
                termuxDpkgEnv()
            )
        } catch (e: Exception) {
            Log.w(TAG, "dpkg --configure fallback failed (non-fatal): ${e.message}")
        }
        publishProgress("bootstrap-configuring", 100, 100)
    }

    private fun ensureTermuxPackageManagerDirectories() {
        listOf(
            "var/lib/dpkg/info",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/log/apt",
            "etc/dpkg/dpkg.cfg.d",
            "etc/apt/apt.conf.d",
            "etc/apt/sources.list.d",
            "etc/apt/preferences.d",
            "tmp"
        ).forEach { relativePath ->
            File(termuxPrefix, relativePath).mkdirs()
        }

        listOf(
            "var/lib/dpkg/lock",
            "var/lib/dpkg/lock-frontend"
        ).forEach { relativePath ->
            val lockFile = File(termuxPrefix, relativePath)
            lockFile.parentFile?.mkdirs()
            if (!lockFile.exists()) {
                lockFile.createNewFile()
            }
        }

        val statusFile = File(termuxPrefix, "var/lib/dpkg/status")
        if (!statusFile.exists()) {
            statusFile.parentFile?.mkdirs()
            statusFile.createNewFile()
        }

        val dpkgCfgDir = File(termuxPrefix, "etc/dpkg/dpkg.cfg.d")
        dpkgCfgDir.mkdirs()
        val dpkgCfg = File(dpkgCfgDir, "01-devpocket")
        dpkgCfg.writeText("force-confnew\n")

        ensureAptConfig()
    }

    private fun ensureAptConfig() {
        val aptConfDir = File(termuxPrefix, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        val aptConf = File(aptConfDir, "01-devpocket")
        aptConf.writeText(
            buildString {
                appendLine("APT::Sandbox::User \"root\";")
                // Set the base Dir so apt resolves all relative paths under the legacy prefix
                // (used when running through the proot compat wrapper)
                appendLine("Dir \"$LEGACY_TERMUX_PREFIX\";")
                appendLine("Dir::Bin::dpkg \"$LEGACY_TERMUX_PREFIX/bin/dpkg\";")
            }
        )
    }

    private fun downloadFile(url: String, outputFile: File) {
        if (outputFile.exists() && outputFile.length() > 0) {
            Log.i(TAG, "Reusing cached download: ${outputFile.absolutePath}")
            return
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Download failed: HTTP ${connection.responseCode} for $url")
        }

        val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: 1L
        var downloadedBytes = 0L
        outputFile.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    publishProgress("bootstrap-downloading", downloadedBytes, totalBytes)
                }
            }
        }
        Log.i(TAG, "Downloaded bootstrap archive to ${outputFile.absolutePath}")
    }

    private fun extractBootstrapZip(zipFile: File, destination: File) {
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        destination.mkdirs()

        ZipFile(zipFile).use { archive ->
            val entries = archive.entries()
            val totalEntries = archive.size().coerceAtLeast(1)
            var processed = 0L

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                writeZipEntry(archive, entry, destination)
                processed += 1
                publishProgress("bootstrap-extracting", processed, totalEntries.toLong())
            }
        }
        Log.i(TAG, "Extracted bootstrap archive to ${destination.absolutePath}")
    }

    private fun writeZipEntry(archive: ZipFile, entry: ZipEntry, destination: File) {
        val sanitized = entry.name.removePrefix("/")
        if (sanitized.isBlank()) {
            return
        }

        val output = File(destination, sanitized)
        val canonicalDest = destination.canonicalPath + File.separator
        val canonicalOutput = output.canonicalPath
        require(canonicalOutput.startsWith(canonicalDest)) { "Blocked path traversal for ${entry.name}" }

        if (entry.isDirectory) {
            output.mkdirs()
            return
        }

        output.parentFile?.mkdirs()
        archive.getInputStream(entry).use { input ->
            FileOutputStream(output).use { outputStream ->
                input.copyTo(outputStream)
            }
        }
    }

    private fun restoreBootstrapSymlinks() {
        val symlinksFile = File(termuxPrefix, "SYMLINKS.txt")
        if (!symlinksFile.exists()) {
            Log.w(TAG, "Bootstrap SYMLINKS.txt missing; nothing to restore")
            return
        }

        symlinksFile.readLines().forEach { line ->
            val parts = line.split('←')
            if (parts.size != 2) {
                return@forEach
            }
            val source = parts[0].trim()
            val linkPath = File(termuxPrefix, parts[1].trim())
            try {
                linkPath.parentFile?.mkdirs()
                linkPath.delete()
                Os.symlink(source, linkPath.absolutePath)
            } catch (e: ErrnoException) {
                Log.w(TAG, "Failed restoring bootstrap symlink ${linkPath.absolutePath}: ${e.message}")
            }
        }
        symlinksFile.delete()
        Log.i(TAG, "Restored bootstrap symlinks")
    }

    private fun patchTextPrefixReferences() {
        var patchedFiles = 0
        val actualPrefix = termuxPrefix.absolutePath
        val actualHome = termuxHome.absolutePath
        val legacyPrefixBytes = LEGACY_TERMUX_PREFIX.toByteArray(StandardCharsets.UTF_8)
        val legacyHomeBytes = LEGACY_TERMUX_HOME.toByteArray(StandardCharsets.UTF_8)

        termuxPrefix.walkTopDown().forEach { file ->
            if (!file.isFile || file.length() > TEXT_PATCH_LIMIT_BYTES) {
                return@forEach
            }

            val raw = try {
                file.readBytes()
            } catch (_: Exception) {
                return@forEach
            }

            if (!raw.containsSlice(legacyPrefixBytes) && !raw.containsSlice(legacyHomeBytes)) {
                return@forEach
            }

            if (!looksLikeText(raw)) {
                return@forEach
            }

            val original = try {
                raw.toString(StandardCharsets.UTF_8)
            } catch (_: Exception) {
                return@forEach
            }

            val patched = original
                .replace(LEGACY_TERMUX_HOME, actualHome)
                .replace(LEGACY_TERMUX_PREFIX, actualPrefix)

            if (patched != original) {
                file.writeText(patched, StandardCharsets.UTF_8)
                patchedFiles += 1
            }
        }

        Log.i(TAG, "Patched $patchedFiles Termux text files to app prefix")
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || this.size < needle.size) {
            return false
        }
        outer@ for (index in 0..this.size - needle.size) {
            for (offset in needle.indices) {
                if (this[index + offset] != needle[offset]) {
                    continue@outer
                }
            }
            return true
        }
        return false
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }
        val sampleLength = minOf(bytes.size, 4096)
        var printable = 0
        for (index in 0 until sampleLength) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 9 || value == 10 || value == 13 || value in 32..126) {
                printable += 1
            }
        }
        return printable.toDouble() / sampleLength.toDouble() > 0.85
    }

    private fun markExecutables(root: File) {
        if (!root.exists()) {
            return
        }

        root.walkTopDown().forEach { file ->
            if (!file.isFile) {
                return@forEach
            }
            if (shouldBeExecutable(file, root)) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
                if (!file.name.endsWith(".so") && !file.name.endsWith(".node")) {
                    file.setWritable(true, true)
                }
            }
        }
    }

    private fun shouldBeExecutable(file: File, root: File): Boolean {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        return relative.startsWith("bin/") ||
            relative.startsWith("libexec/") ||
            file.name.endsWith(".so") ||
            file.name.endsWith(".node")
    }

    private fun ensureHostPackagesInstalled() {
        val proot = File(termuxBin, "proot")
        val prootDistro = File(termuxBin, "proot-distro")
        val node = File(termuxBin, "node")
        if (proot.exists() && prootDistro.exists() && node.exists()) {
            Log.i(TAG, "Required Termux host packages already installed")
            return
        }

        // Pre-flight: verify the proot compat wrapper works with Termux bash (not just a system binary)
        try {
            runTermuxCompatCommand(
                listOf(File(termuxBin, "bash").absolutePath, "-c", "echo proot-compat-test-ok"),
                "proot-preflight"
            )
            Log.i(TAG, "Proot compatibility wrapper pre-flight passed (Termux bash works under proot)")
        } catch (e: Exception) {
            Log.e(TAG, "Proot compatibility wrapper pre-flight FAILED: ${e.message}. " +
                "proot=${runtimeProot.absolutePath} exists=${runtimeProot.exists()} exec=${runtimeProot.canExecute()}")
            throw IllegalStateException("Proot compatibility wrapper is not functional: ${e.message}", e)
        }

        publishProgress("termux-updating", 0, 100)
        try {
            runTermuxCompatShellCommand("pkg update -y", "termux-updating", termuxDpkgEnv())
        } catch (e: Exception) {
            Log.w(TAG, "pkg update failed on first attempt: ${e.message}; retrying once")
            runTermuxCompatShellCommand("pkg update -y", "termux-updating", termuxDpkgEnv())
        }
        publishProgress("termux-updating", 100, 100)

        // Install packages individually for better error isolation
        val packages = TERMUX_PACKAGES.split(" ").filter { it.isNotBlank() }
        publishProgress("termux-installing-packages", 0, 100)
        for ((index, pkg) in packages.withIndex()) {
            val pkgBin = File(termuxBin, pkg)
            if (pkgBin.exists()) {
                Log.i(TAG, "Package '$pkg' already present at ${pkgBin.absolutePath}")
            } else {
                runTermuxCompatShellCommand("pkg install -y $pkg", "termux-installing-$pkg", termuxDpkgEnv())
            }
            publishProgress("termux-installing-packages",
                ((index + 1).toLong() * 100) / packages.size, 100)
        }
        publishProgress("termux-installing-packages", 100, 100)

        patchTextPrefixReferences()
        markExecutables(termuxPrefix)
    }

    private fun ensureDebianInstalled() {
        if (File(debianRoot, "etc").exists()) {
            Log.i(TAG, "Debian is already installed at ${debianRoot.absolutePath}")
            return
        }

        publishProgress("debian-installing", 0, 100)
        runTermuxCompatCommand(listOf(File(termuxBin, "proot-distro").absolutePath, "install", DEBIAN_ALIAS), "debian-installing")
        publishProgress("debian-installing", 100, 100)
    }

    private fun finalizeDebianEnvironment() {
        val rootHome = File(debianRoot, "root")
        rootHome.mkdirs()

        val bashrc = File(rootHome, ".bashrc")
        val marker = "# DevPocket integration"
        val addition = buildString {
            appendLine()
            appendLine(marker)
            appendLine("alias android='cd /sdcard'")
            appendLine("export TERM=xterm-256color")
            appendLine("export COLORTERM=truecolor")
            appendLine("export LANG=C.UTF-8")
            appendLine("export LC_ALL=C.UTF-8")
            appendLine("export ANDROID_STORAGE=/sdcard")
            appendLine("export SSL_CERT_FILE=/opt/devpocket/etc/ca-certificates/cacert.pem")
            appendLine("export GIT_SSL_CAINFO=/opt/devpocket/etc/ca-certificates/cacert.pem")
            appendLine("export CURL_CA_BUNDLE=/opt/devpocket/etc/ca-certificates/cacert.pem")
            appendLine("export NODE_EXTRA_CA_CERTS=/opt/devpocket/etc/ca-certificates/cacert.pem")
        }
        val originalBashrc = if (bashrc.exists()) bashrc.readText() else ""
        if (!originalBashrc.contains(marker)) {
            bashrc.writeText(originalBashrc + addition)
        }

        val bashProfile = File(rootHome, ".bash_profile")
        if (!bashProfile.exists()) {
            bashProfile.writeText("if [ -f ~/.bashrc ]; then\n    . ~/.bashrc\nfi\n")
        }

        createOrReplaceSymlink(File(rootHome, "storage"), "/sdcard")
        createOrReplaceSymlink(File(rootHome, "Download"), "/sdcard/Download")
        createOrReplaceSymlink(File(rootHome, "Downloads"), "/sdcard/Download")
        createOrReplaceSymlink(File(rootHome, "Documents"), "/sdcard/Documents")
        createOrReplaceSymlink(File(rootHome, "Pictures"), "/sdcard/Pictures")

        writeWrapperScripts()
        Log.i(TAG, "Finalized Debian root environment at ${rootHome.absolutePath}")
    }

    private fun createOrReplaceSymlink(link: File, target: String) {
        try {
            if (link.isDirectory) {
                link.deleteRecursively()
            } else {
                link.delete()
            }
            link.parentFile?.mkdirs()
            Os.symlink(target, link.absolutePath)
        } catch (e: ErrnoException) {
            Log.w(TAG, "Failed to create symlink ${link.absolutePath} -> $target: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare shortcut ${link.absolutePath}: ${e.message}")
        }
    }

    private fun writeWrapperScripts() {
        termuxBin.mkdirs()

        val envWrapper = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("ORIG_PATH=\"${'$'}PATH\"")
            appendLine("ORIG_LD_LIBRARY_PATH=\"${'$'}LD_LIBRARY_PATH\"")
            appendLine("export PREFIX=\"${termuxPrefix.absolutePath}\"")
            appendLine("export HOME=\"${termuxHome.absolutePath}\"")
            appendLine("export TMPDIR=\"${termuxTmp.absolutePath}\"")
            appendLine("TERMUX_UID=${'$'}(id -u 2>/dev/null || true)")
            appendLine("if [ -n \"${'$'}TERMUX_UID\" ]; then")
            appendLine("  export TERMUX__UID=\"${'$'}TERMUX_UID\"")
            appendLine("  export TERMUX__USER_ID=\"${'$'}TERMUX_UID\"")
            appendLine("fi")
            appendLine("if [ -n \"${'$'}ORIG_PATH\" ]; then")
            appendLine("  export PATH=\"${termuxBin.absolutePath}:/system/bin:${'$'}ORIG_PATH\"")
            appendLine("else")
            appendLine("  export PATH=\"${termuxBin.absolutePath}:/system/bin\"")
            appendLine("fi")
            appendLine("if [ -n \"${'$'}ORIG_LD_LIBRARY_PATH\" ]; then")
            appendLine("  export LD_LIBRARY_PATH=\"${termuxLib.absolutePath}:${'$'}ORIG_LD_LIBRARY_PATH\"")
            appendLine("else")
            appendLine("  export LD_LIBRARY_PATH=\"${termuxLib.absolutePath}\"")
            appendLine("fi")
            appendLine("export LANG=\"C.UTF-8\"")
            appendLine("export LC_ALL=\"C.UTF-8\"")
            appendLine("export TERM=\"xterm-256color\"")
            appendLine("export COLORTERM=\"truecolor\"")
            appendLine("export ANDROID_STORAGE=\"/sdcard\"")
            appendLine("mkdir -p \"${termuxHome.absolutePath}\" \"${termuxTmp.absolutePath}\" 2>/dev/null")
            appendLine("if [ -f \"${termuxPrefix.absolutePath}/etc/tls/cert.pem\" ]; then")
            appendLine("  export SSL_CERT_FILE=\"${termuxPrefix.absolutePath}/etc/tls/cert.pem\"")
            appendLine("  export GIT_SSL_CAINFO=\"${termuxPrefix.absolutePath}/etc/tls/cert.pem\"")
            appendLine("  export CURL_CA_BUNDLE=\"${termuxPrefix.absolutePath}/etc/tls/cert.pem\"")
            appendLine("  export NODE_EXTRA_CA_CERTS=\"${termuxPrefix.absolutePath}/etc/tls/cert.pem\"")
            appendLine("fi")
            appendLine("exec \"${'$'}@\"")
        }
        termuxEnvWrapper.writeText(envWrapper)
        termuxEnvWrapper.setExecutable(true, false)

        val compatWrapper = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("PROOT_BIN=\"${runtimeProot.absolutePath}\"")
            appendLine("APP_FILES=\"${context.filesDir.absolutePath}\"")
            appendLine("APP_CACHE=\"${context.cacheDir.absolutePath}\"")
            appendLine("HOST_PREFIX=\"${termuxPrefix.absolutePath}\"")
            appendLine("HOST_HOME=\"${termuxHome.absolutePath}\"")
            appendLine("HOST_TMP=\"${termuxTmp.absolutePath}\"")
            appendLine("LEGACY_FILES=\"$LEGACY_TERMUX_FILES\"")
            appendLine("LEGACY_CACHE=\"$LEGACY_TERMUX_CACHE\"")
            appendLine("LEGACY_PREFIX=\"$LEGACY_TERMUX_PREFIX\"")
            appendLine("LEGACY_HOME=\"$LEGACY_TERMUX_HOME\"")
            appendLine("LEGACY_TMP=\"$LEGACY_TERMUX_TMP\"")
            appendLine("mkdir -p \"${termuxCompatCache.absolutePath}\" \"${termuxHome.absolutePath}\" \"${termuxTmp.absolutePath}\" 2>/dev/null")
            appendLine("export PROOT_TMP_DIR=\"${termuxCompatCache.absolutePath}\"")
            appendLine("export PROOT_NO_SECCOMP=1")
            appendLine("if [ ! -x \"\${PROOT_BIN}\" ]; then")
            appendLine("  echo \"DevPocket error: missing bundled compatibility proot at \${PROOT_BIN}\" >&2")
            appendLine("  exit 127")
            appendLine("fi")
            // Set all env vars BEFORE proot exec — proot inherits them from the parent process.
            // This eliminates the fragile intermediate /system/bin/sh -c wrapper.
            appendLine("export PREFIX=\"\${LEGACY_PREFIX}\"")
            appendLine("export HOME=\"\${LEGACY_HOME}\"")
            appendLine("export TMPDIR=\"\${LEGACY_TMP}\"")
            appendLine("export PATH=\"\${LEGACY_PREFIX}/bin:\${HOST_PREFIX}/bin:/system/bin\"")
            appendLine("export LD_LIBRARY_PATH=\"\${LEGACY_PREFIX}/lib:\${HOST_PREFIX}/lib\"")
            appendLine("export LANG=C.UTF-8")
            appendLine("export LC_ALL=C.UTF-8")
            appendLine("export TERM=xterm-256color")
            appendLine("export COLORTERM=truecolor")
            appendLine("export ANDROID_STORAGE=/sdcard")
            appendLine("if [ -f \"\${LEGACY_PREFIX}/etc/tls/cert.pem\" ]; then")
            appendLine("  export SSL_CERT_FILE=\"\${LEGACY_PREFIX}/etc/tls/cert.pem\"")
            appendLine("  export GIT_SSL_CAINFO=\"\${LEGACY_PREFIX}/etc/tls/cert.pem\"")
            appendLine("  export CURL_CA_BUNDLE=\"\${LEGACY_PREFIX}/etc/tls/cert.pem\"")
            appendLine("  export NODE_EXTRA_CA_CERTS=\"\${LEGACY_PREFIX}/etc/tls/cert.pem\"")
            appendLine("fi")
            appendLine("TERMUX_UID=\$(id -u 2>/dev/null || true)")
            appendLine("if [ -n \"\${TERMUX_UID}\" ]; then")
            appendLine("  export TERMUX__UID=\"\${TERMUX_UID}\"")
            appendLine("  export TERMUX__USER_ID=\"\${TERMUX_UID}\"")
            appendLine("fi")
            // Forward DPKG env vars from ProcessBuilder.environment() into the proot guest
            appendLine("if [ -n \"\${DPKG_ROOT}\" ]; then export DPKG_ROOT DPKG_ADMINDIR DPKG_FORCE; fi")
            appendLine("echo \"DevPocket: host Termux compatibility mode (launcher=\${PROOT_BIN} host-prefix=\${HOST_PREFIX} legacy-prefix=\${LEGACY_PREFIX} cmd=\$*)\" >&2")
            // Removed: redundant self-binds (-b $APP_FILES:$APP_FILES) that confused proot path table
            // Removed: intermediate /system/bin/sh -c wrapper — env vars are inherited directly
            // --link2symlink is needed because dpkg uses hardlinks and Android's FS may not support them
            appendLine("exec \"\${PROOT_BIN}\" --kill-on-exit --link2symlink -0 -r / \\")
            appendLine("  -b /system -b /apex -b /dev -b /proc -b /sys -b /sdcard -b /storage \\")
            appendLine("  -b \"${context.filesDir.absolutePath}:\${LEGACY_FILES}\" \\")
            appendLine("  -b \"${context.cacheDir.absolutePath}:\${LEGACY_CACHE}\" \\")
            appendLine("  -w / \\")
            appendLine("  \"\$@\"")
        }
        termuxCompatWrapper.writeText(compatWrapper)
        termuxCompatWrapper.setExecutable(true, false)

        val shellWrapper = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("APP_FILES=\"${context.filesDir.absolutePath}\"")
            appendLine("PREFIX=\"${termuxPrefix.absolutePath}\"")
            appendLine("TERMUX_ENV=\"${termuxEnvWrapper.absolutePath}\"")
            appendLine("TERMUX_COMPAT=\"${termuxCompatWrapper.absolutePath}\"")
            appendLine("LEGACY_PROOT_DISTRO=\"$LEGACY_TERMUX_PREFIX/bin/proot-distro\"")
            appendLine("DEBIAN_ROOT=\"${debianRoot.absolutePath}\"")
            appendLine("RUNTIME_ROOT=\"${TheiaRuntimePaths.runtimeRoot(context).absolutePath}\"")
            appendLine("CONFIG_DIR=\"${TheiaRuntimePaths.configDir(context).absolutePath}\"")
            appendLine("EXTENSIONS_DIR=\"${TheiaRuntimePaths.extensionsRoot(context).absolutePath}\"")
            appendLine("if [ ! -x \"${termuxCompatWrapper.absolutePath}\" ]; then")
            appendLine("  echo \"DevPocket error: missing Termux compatibility wrapper at ${termuxCompatWrapper.absolutePath}\" >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("if [ ! -x \"${termuxBin.absolutePath}/proot-distro\" ]; then")
            appendLine("  echo \"DevPocket error: missing proot-distro at ${termuxBin.absolutePath}/proot-distro\" >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("if [ ! -d \"${debianRoot.absolutePath}\" ]; then")
            appendLine("  echo \"DevPocket error: Debian rootfs is not installed yet\" >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("HOST_PWD=\$(pwd 2>/dev/null || printf '%s' \"${debianRoot.absolutePath}/root\")")
            appendLine("GUEST_WD=\"/root\"")
            appendLine("case \"\${HOST_PWD}\" in")
            appendLine("  \"${debianRoot.absolutePath}\") GUEST_WD=\"/\" ;;")
            appendLine("  \"${debianRoot.absolutePath}\"/*) GUEST_WD=\"/\${HOST_PWD#${debianRoot.absolutePath}/}\" ;;")
            appendLine("  /sdcard|/sdcard/*|/storage|/storage/*) GUEST_WD=\"\${HOST_PWD}\" ;;")
            appendLine("esac")
            appendLine("if [ \"\$#\" -eq 0 ]; then")
            appendLine("  set -- --login")
            appendLine("fi")
            appendLine("PROOT_GETCWD_SHIM=\"/opt/devpocket/bin/proot-getcwd.so\"")
            appendLine("PROOT_GETCWD_PRELOAD=\"\"")
            appendLine("if [ -f \"\${PROOT_GETCWD_SHIM}\" ]; then")
            appendLine("  PROOT_GETCWD_PRELOAD=\"\${PROOT_GETCWD_SHIM}\"")
            appendLine("fi")
            appendLine("echo \"DevPocket: entering Debian via proot-distro (host-pwd=\${HOST_PWD} guest-pwd=\${GUEST_WD})\" >&2")
            appendLine("exec \"${termuxCompatWrapper.absolutePath}\" \"\${LEGACY_PROOT_DISTRO}\" login --shared-tmp \\")
            appendLine("  --bind /sdcard:/sdcard \\")
            appendLine("  --bind /storage:/storage \\")
            appendLine("  --bind \"\${RUNTIME_ROOT}:/opt/devpocket\" \\")
            appendLine("  --bind \"\${CONFIG_DIR}:/opt/devpocket-config\" \\")
            appendLine("  --bind \"\${EXTENSIONS_DIR}:/opt/devpocket-extensions\" \\")
            appendLine("  --work-dir \"\${GUEST_WD}\" ${DEBIAN_ALIAS} -- /usr/bin/env \\")
            appendLine("  HOME=/root USER=root LOGNAME=root TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8 LC_ALL=C.UTF-8 ANDROID_STORAGE=/sdcard \\")
            appendLine("  SSL_CERT_FILE=/opt/devpocket/etc/ca-certificates/cacert.pem GIT_SSL_CAINFO=/opt/devpocket/etc/ca-certificates/cacert.pem CURL_CA_BUNDLE=/opt/devpocket/etc/ca-certificates/cacert.pem NODE_EXTRA_CA_CERTS=/opt/devpocket/etc/ca-certificates/cacert.pem LD_PRELOAD=\${PROOT_GETCWD_PRELOAD} \\")
            appendLine("  /bin/bash \"\${@}\"")
        }
        termuxShellWrapper.writeText(shellWrapper)
        termuxShellWrapper.setExecutable(true, false)
        Log.i(TAG, "Wrote Termux wrappers at ${termuxEnvWrapper.absolutePath}, ${termuxCompatWrapper.absolutePath}, and ${termuxShellWrapper.absolutePath}")
    }

    private fun termuxDpkgEnv(): Map<String, String> = mapOf(
        "DPKG_ROOT" to LEGACY_TERMUX_PREFIX,
        "DPKG_ADMINDIR" to "$LEGACY_TERMUX_PREFIX/var/lib/dpkg",
        "DPKG_FORCE" to "script-chrootless"
    )

    /** DPKG env vars using the real app-prefix paths (for direct execution without proot). */
    private fun termuxDpkgEnvLocal(): Map<String, String> = mapOf(
        "DPKG_ROOT" to termuxPrefix.absolutePath,
        "DPKG_ADMINDIR" to "${termuxPrefix.absolutePath}/var/lib/dpkg",
        "DPKG_FORCE" to "script-chrootless"
    )

    private fun toCompatGuestPath(value: String): String {
        return when {
            value == termuxPrefix.absolutePath -> LEGACY_TERMUX_PREFIX
            value.startsWith(termuxPrefix.absolutePath + "/") ->
                "$LEGACY_TERMUX_PREFIX/" + value.removePrefix(termuxPrefix.absolutePath + "/")
            value == termuxHome.absolutePath -> LEGACY_TERMUX_HOME
            value.startsWith(termuxHome.absolutePath + "/") ->
                "$LEGACY_TERMUX_HOME/" + value.removePrefix(termuxHome.absolutePath + "/")
            value == termuxTmp.absolutePath -> LEGACY_TERMUX_TMP
            value.startsWith(termuxTmp.absolutePath + "/") ->
                "$LEGACY_TERMUX_TMP/" + value.removePrefix(termuxTmp.absolutePath + "/")
            value == context.filesDir.absolutePath -> LEGACY_TERMUX_FILES
            value.startsWith(context.filesDir.absolutePath + "/") ->
                "$LEGACY_TERMUX_FILES/" + value.removePrefix(context.filesDir.absolutePath + "/")
            value == context.cacheDir.absolutePath -> LEGACY_TERMUX_CACHE
            value.startsWith(context.cacheDir.absolutePath + "/") ->
                "$LEGACY_TERMUX_CACHE/" + value.removePrefix(context.cacheDir.absolutePath + "/")
            else -> value
        }
    }

    private fun runTermuxCompatShellCommand(
        command: String,
        phase: String,
        extraEnv: Map<String, String> = emptyMap()
    ) {
        runTermuxCompatCommand(listOf(File(termuxBin, "bash").absolutePath, "-lc", command), phase, extraEnv)
    }

    private fun runTermuxCompatCommand(
        command: List<String>,
        phase: String,
        extraEnv: Map<String, String> = emptyMap()
    ) {
        runTermuxCommand(command.map(::toCompatGuestPath), phase, extraEnv + termuxDpkgEnv(), termuxCompatWrapper)
    }

    private fun runTermuxShellCommand(
        command: String,
        phase: String,
        extraEnv: Map<String, String> = emptyMap()
    ) {
        runTermuxCommand(listOf(File(termuxBin, "bash").absolutePath, "-lc", command), phase, extraEnv)
    }

    private fun runTermuxCommand(
        command: List<String>,
        phase: String,
        extraEnv: Map<String, String> = emptyMap(),
        launcher: File = termuxEnvWrapper
    ) {
        Log.i(TAG, "Running $phase command: ${command.joinToString(" ")}")

        val builder = ProcessBuilder(listOf(launcher.absolutePath) + command)
        builder.directory(termuxHome)
        builder.redirectErrorStream(true)
        builder.environment().putAll(extraEnv)

        val process = builder.start()
        rememberCommandOutput("[$phase] $ ${command.joinToString(" ")}")
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.i(TAG, "[$phase] $line")
                rememberCommandOutput("[$phase] $line")
            }
        }

        val exitCode = process.waitFor()
        Log.i(TAG, "$phase command exited with code $exitCode")
        if (exitCode != 0) {
            throw IllegalStateException(
                "$phase command failed with exit code $exitCode\n" + recentDiagnosticsSummary()
            )
        }
    }

    private fun rememberCommandOutput(line: String) {
        if (recentCommandOutput.size >= MAX_DIAGNOSTIC_LINES) {
            recentCommandOutput.removeFirst()
        }
        recentCommandOutput.addLast(line)
    }

    private fun recentDiagnosticsSummary(): String {
        if (recentCommandOutput.isEmpty()) {
            return "No installer output was captured."
        }
        return recentCommandOutput.joinToString(separator = "\n")
    }

    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var bytesRead: Int

        FileInputStream(file).use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeManifest(manifest: InstallManifest) {
        val json = buildString {
            appendLine("{")
            appendLine("  \"version\": \"${manifest.version}\",")
            appendLine("  \"bootstrapVersion\": \"${manifest.bootstrapVersion}\",")
            appendLine("  \"installedAt\": ${manifest.installedAt},")
            appendLine("  \"prefixPath\": \"${manifest.prefixPath}\",")
            appendLine("  \"debianPath\": \"${manifest.debianPath}\",")
            appendLine("  \"hostPackages\": \"${manifest.hostPackages}\",")
            appendLine("  \"extractedSuccessfully\": ${manifest.extractedSuccessfully}")
            appendLine("}")
        }
        manifestFile.writeText(json)
        Log.i(TAG, "Wrote install manifest to ${manifestFile.absolutePath}")
    }

    private fun publishProgress(phase: String, current: Long, total: Long) {
        val percent = if (total > 0) ((current.toFloat() / total) * 100).toInt() else 0
        stateManager.setInstallProgress(percent.coerceIn(0, 100))
        progressCallback?.invoke(InstallProgress(phase, current, total, percent.coerceIn(0, 100)))
    }
}
