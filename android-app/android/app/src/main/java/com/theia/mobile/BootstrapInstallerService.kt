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
import java.io.IOException
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
        private const val DEBIAN_GIT_PACKAGE = "git"
        private const val DEBIAN_GIT_BINARY_PATH = "usr/bin/git"
        private const val SHELL_WRAPPER_HOST_CWD_MARKER = "HOST_PWD_ORIGINAL="
        private const val ROOT_GIT_CONFIG_PATH = "/root/.gitconfig"
        private const val ROOT_GIT_CONFIG_PLACEHOLDER = "# DevPocket Git config placeholder\n"
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

    fun ensureRuntimeCompatibility(): Boolean {
        return if (needsRuntimeRefresh()) {
            Log.i(TAG, "Refreshing runtime integration for current app build")
            repair()
        } else {
            Log.i(TAG, "Runtime integration already current")
            true
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
        File(termuxHome, ".termux").mkdirs()
        termuxTmp.mkdirs()
        termuxCompatCache.mkdirs()
        TheiaRuntimePaths.configDir(context).mkdirs()
        TheiaRuntimePaths.extensionsRoot(context).mkdirs()
    }

    private fun needsRuntimeRefresh(): Boolean {
        if (!termuxShellWrapper.exists()) {
            return true
        }

        val shellWrapperText = runCatching { termuxShellWrapper.readText() }.getOrDefault("")
        if (!shellWrapperText.contains("GIT_CONFIG_NOSYSTEM=1") ||
            !shellWrapperText.contains("GIT_CONFIG_GLOBAL=$ROOT_GIT_CONFIG_PATH") ||
            !shellWrapperText.contains(SHELL_WRAPPER_HOST_CWD_MARKER)) {
            return true
        }

        if (debianRoot.exists()) {
            if (!File(debianRoot, "root/.gitconfig").exists() ||
                !hasDebianGitBinary(debianRoot)) {
                return true
            }
            // Trigger repair if dpkg is not yet held — otherwise apt-get install
            // tries to upgrade dpkg itself inside proot and fails with exit code 100.
            if (!isDebianDpkgHeld()) {
                return true
            }
            // Trigger repair if policy-rc.d is missing — this blocks service starts
            // during postinst (dbus, etc.) which fail inside proot without an init
            // system. Creating it and re-running dpkg --configure --pending fixes
            // any half-configured packages left from a previous apt-get install.
            if (!File(debianRoot, "usr/sbin/policy-rc.d").exists()) {
                return true
            }
            // Trigger repair if any packages are still half-configured — dpkg will
            // refuse to install new packages until all pending configurations are
            // resolved. holdDebianCorePackages() fake-configures problem packages.
            if (hasHalfConfiguredDebianPackages()) {
                return true
            }
            // Trigger repair if the dpkg status file is missing its trailing newline.
            // holdDebianCorePackages() used joinToString("\n") which strips the final
            // \n — dpkg refuses to parse the file, blocking all apt-get installs.
            if (isDebianDpkgStatusCorrupt()) {
                return true
            }
        }

        return false
    }

    /** True if any packages are in half-configured or unpacked state in Debian dpkg. */
    private fun hasHalfConfiguredDebianPackages(): Boolean {
        val statusFile = File(debianRoot, "var/lib/dpkg/status")
        if (!statusFile.exists()) return false
        val text = runCatching { statusFile.readText(Charsets.UTF_8) }.getOrDefault("")
        return text.contains("half-configured") || text.contains("ok unpacked")
    }

    /**
     * Returns true when dpkg's status in the Debian rootfs is "hold ok installed".
     * Used by needsRuntimeRefresh() to detect that holdDebianCorePackages() has run.
     */
    private fun isDebianDpkgHeld(): Boolean {
        val statusFile = File(debianRoot, "var/lib/dpkg/status")
        if (!statusFile.exists()) return false
        val text = runCatching { statusFile.readText(Charsets.UTF_8) }.getOrDefault("")
        // Find the dpkg package block and check its Status line.
        val dpkgBlock = text.substringAfter("Package: dpkg\n", "")
        // The next "Package: " boundary marks the end of this block.
        val blockEnd = dpkgBlock.indexOf("\nPackage: ").let { if (it < 0) dpkgBlock.length else it }
        return dpkgBlock.substring(0, blockEnd).contains("Status: hold")
    }

    /**
     * Returns true when the dpkg status file is missing its trailing newline.
     * holdDebianCorePackages() previously used joinToString("\n") without "+ "\n"",
     * which strips the final newline — dpkg refuses to parse a file that doesn't end
     * with \n and prints "end of file during value of field '...' (missing final newline)".
     */
    private fun isDebianDpkgStatusCorrupt(): Boolean {
        val statusFile = File(debianRoot, "var/lib/dpkg/status")
        if (!statusFile.exists() || statusFile.length() == 0L) return false
        return runCatching {
            java.io.RandomAccessFile(statusFile, "r").use { f ->
                f.seek(f.length() - 1)
                f.readByte() != '\n'.code.toByte()
            }
        }.getOrDefault(false)
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
            // The Termux second-stage script creates its own lock file internally even
            // when it fails. Delete it so the direct fallback can attempt a fresh run.
            secondStageLock.delete()
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
                    "dpkg --configure -a --force-all || true"),
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
                appendLine("DPkg::Options { \"--force-confnew\"; \"--force-script-chrootless\"; \"--force-unsafe-io\"; \"--force-overwrite\"; };")
            }
        )

        // Ensure directories that dpkg and update-alternatives need
        File(termuxPrefix, "var/log").mkdirs()
        File(termuxPrefix, "var/lib/dpkg/updates").mkdirs()
        File(termuxPrefix, "var/lib/dpkg/info").mkdirs()
        File(termuxPrefix, "var/lib/dpkg/triggers").mkdirs()
        File(termuxPrefix, "tmp").mkdirs()

        // Ensure Termux package repositories are configured
        val sourcesList = File(termuxPrefix, "etc/apt/sources.list")
        sourcesList.parentFile?.mkdirs()
        sourcesList.writeText(
            buildString {
                appendLine("# Main Termux repository")
                appendLine("deb [trusted=yes] https://packages.termux.dev/apt/termux-main stable main")
            }
        )
        // Clean up any stale sources.list.d entries from previous installs
        val sourcesListDir = File(termuxPrefix, "etc/apt/sources.list.d")
        sourcesListDir.mkdirs()
        sourcesListDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Configured Termux package repositories (main with trusted=yes)")
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

        if (!termuxPrefix.isDirectory) {
            Log.w(TAG, "termuxPrefix is not a directory, skipping text patch")
            return
        }
        termuxPrefix.walkTopDown()
            // Skip symlinked directories — their targets may not exist on the host
            // filesystem (Termux bootstrap creates symlinks like lib → usr/lib pointing
            // to /data/data/com.termux paths that don't exist in our app sandbox).
            // Following them causes AssertionError in FileTreeWalk.
            .onEnter { dir -> !java.nio.file.Files.isSymbolicLink(dir.toPath()) }
            .forEach { file ->
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

            var patched = original
                .replace(LEGACY_TERMUX_HOME, actualHome)
                .replace(LEGACY_TERMUX_PREFIX, actualPrefix)
            
            // Apply targeted fixes for proot-distro if this is the proot-distro script
            if (file.name == "proot-distro") {
                // Remove "proot --link2symlink \" wrapper line so tar runs natively.
                // The next line (tar -C ...) becomes a standalone command.
                patched = patched.lines().filter { line ->
                    !line.trim().startsWith("proot --link2symlink")
                }.joinToString("\n")
            }

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

        root.walkTopDown()
            .onEnter { dir -> !java.nio.file.Files.isSymbolicLink(dir.toPath()) }
            .forEach { file ->
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
            relative.startsWith("lib/apt/") ||
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

        // Clean up any dpkg state left by a partial second-stage run (e.g. when proot's
        // uid spoofing (-0) fails on some devices and the second-stage script exits early
        // on the uid check). Without this, apt update returns exit code 100 (dpkg error).
        try {
            runTermuxCompatShellCommand(
                "dpkg --configure -a --force-all 2>/dev/null || true",
                "termux-dpkg-configure-pre-update",
                termuxDpkgEnv()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Pre-update dpkg configure failed (non-fatal): ${e.message}")
        }

        publishProgress("termux-updating", 0, 100)
        try {
            runTermuxCompatShellCommand("apt -o Acquire::AllowInsecureRepositories=true update -y", "termux-updating", termuxDpkgEnv())
        } catch (e: Exception) {
            Log.w(TAG, "apt update attempt 1 failed: ${e.message}; retrying")
            try {
                runTermuxCompatShellCommand("apt -o Acquire::AllowInsecureRepositories=true update -y", "termux-updating", termuxDpkgEnv())
            } catch (e2: Exception) {
                // apt update failure is non-fatal — the package cache may be stale but
                // apt-get -d (download-only) in the next step often still succeeds with
                // the existing lists. Log and continue rather than aborting onboarding.
                Log.w(TAG, "apt update failed on both attempts (continuing): ${e2.message}")
            }
        }
        publishProgress("termux-updating", 100, 100)

        // Fix GNU tar issue with dpkg by removing the incompatible flag via a wrapper.
        // This allows us to use dpkg-deb -x natively without proot crashing.
        val tarFile = File(termuxBin, "tar")
        val tarRealFile = File(termuxBin, "tar.real")
        if (tarFile.exists() && !tarRealFile.exists()) {
            tarFile.renameTo(tarRealFile)
            tarFile.writeText(
                """#!/system/bin/sh
                |ARGS=""
                |for arg in "${'$'}@"; do
                |  if [ "${'$'}arg" != "--warning=no-timestamp" ]; then
                |    ARGS="${'$'}ARGS ${'$'}arg"
                |  fi
                |done
                |exec ${tarRealFile.absolutePath} ${'$'}ARGS
                """.trimMargin()
            )
            tarFile.setExecutable(true, false)
        }

        // Install packages using native download-then-extract approach.
        // apt internally fails under PRoot, so we:
        //   1. Download .deb files via apt-get -d (under proot is fine since no forking dpkg)
        //   2. Extract them manually via native dpkg-deb -x
        //   3. Configure them using PRoot
        val packages = TERMUX_PACKAGES.split(" ").filter { it.isNotBlank() }
        publishProgress("termux-installing-packages", 0, 100)

        // Step 1: Download packages
        Log.i(TAG, "Downloading packages: ${packages.joinToString(", ")}")
        try {
            runTermuxCompatShellCommand(
                "apt-get -o Dpkg::Use-Pty=0 -y -d --allow-unauthenticated install ${packages.joinToString(" ")}",
                "termux-downloading-packages"
            )
        } catch (e: Exception) {
            Log.w(TAG, "apt-get download exited non-zero (may be partial): ${e.message}")
        }
        publishProgress("termux-installing-packages", 40, 100)

        // Step 2: Native Extract
        Log.i(TAG, "Extracting downloaded packages natively")
        // apt-get stores downloaded .deb files in either $PREFIX/var/cache/apt/archives
        // or context.cacheDir/apt/archives depending on how the proot compat bind-mounts map.
        // Check which directory actually has .deb files (not just the directory existing,
        // since the primary path may exist but only contain an empty 'partial/' subdirectory).
        val archivesDirPrimary = File(termuxPrefix, "var/cache/apt/archives")
        val archivesDirFallback = File(context.cacheDir, "apt/archives")
        val primaryHasDebs = archivesDirPrimary.exists() &&
            archivesDirPrimary.listFiles { f -> f.name.endsWith(".deb") }?.isNotEmpty() == true
        val archivesDir = if (primaryHasDebs) archivesDirPrimary else archivesDirFallback
        if (archivesDir.exists()) {
            val debs = archivesDir.listFiles { file -> file.name.endsWith(".deb") }
            if (debs != null) {
                for (deb in debs) {
                    Log.i(TAG, "Natively extracting ${deb.name}")
                    try {
                        val extractTmp = File(context.cacheDir, "dpkg-extract")
                        extractTmp.deleteRecursively()
                        if (!extractTmp.exists()) extractTmp.mkdirs()
                        
                        runTermuxShellCommand(
                            "dpkg-deb -x ${deb.absolutePath} ${extractTmp.absolutePath}",
                            "extract-${deb.name}"
                        )
                        
                        val innerUsr = File(extractTmp.absolutePath, "data/data/com.termux/files/usr")
                        if (innerUsr.exists()) {
                            runTermuxShellCommand(
                                "cp -rf ${innerUsr.absolutePath}/* ${termuxPrefix.absolutePath}/",
                                "copy-${deb.name}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed natively extracting ${deb.name}: ${e.message}")
                    }
                }
            }
        }
        publishProgress("termux-installing-packages", 70, 100)

        // Step 3: Configure partially-installed packages
        // Configure using the proot wrapper, because postinst scripts need paths intercepted
        try {
            runTermuxCompatShellCommand(
                "dpkg --configure -a --force-all",
                "termux-dpkg-configure"
            )
        } catch (e: Exception) {
            Log.w(TAG, "dpkg --configure -a: ${e.message}")
        }
        publishProgress("termux-installing-packages", 90, 100)

        // Step 4: Seed required binaries from bundled assets if apt install was partial/failed.
        // This makes bootstrap reliable on all devices regardless of apt/dpkg state.
        seedRequiredBinariesFromAssets()
        publishProgress("termux-installing-packages", 100, 100)

        // Restore tar after we natively bypassed dpkg-deb issues,
        // so that proot-distro can use the native ELF tar under its internal proot invocation.
        if (tarRealFile.exists()) {
            tarFile.delete()
            tarRealFile.renameTo(tarFile)
        }

        patchTextPrefixReferences()
        markExecutables(termuxPrefix)
    }

    /**
     * Seeds proot, proot-distro, and node into the Termux prefix from bundled/downloaded sources.
     * Called after apt-get install so apt gets first crack, but this guarantees the binaries
     * are present even when apt/dpkg fails (wrong uid, broken dpkg state, no network, etc.).
     *
     * - proot     : copied from the bundled statically-linked runtime binary (always works)
     * - node      : copied from the bundled runtime binary (always works)
     * - proot-distro : shell script downloaded from GitHub (tiny text file, one URL)
     */
    private fun seedRequiredBinariesFromAssets() {
        // ── proot ──────────────────────────────────────────────────────────────────────
        val termuxProot = File(termuxBin, "proot")
        if (runtimeProot.exists()) {
            runtimeProot.copyTo(termuxProot, overwrite = true)
            termuxProot.setExecutable(true, false)
            Log.i(TAG, "Seeded proot from runtime: ${termuxProot.absolutePath}")
        } else {
            Log.e(TAG, "Runtime proot missing — cannot seed proot")
            throw IllegalStateException("Bundled proot binary not found at ${runtimeProot.absolutePath}")
        }

        // ── node ───────────────────────────────────────────────────────────────────────
        // The runtime has node (wrapper script) and node.real (ELF). Copy the wrapper so
        // LD_LIBRARY_PATH and LD_PRELOAD are handled correctly when run outside proot.
        val termuxNode = File(termuxBin, "node")
        val runtimeBin = File(TheiaRuntimePaths.runtimeRoot(context), "bin")
        val runtimeNodeWrapper = File(runtimeBin, "node")
        val runtimeNodeReal    = File(runtimeBin, "node.real")
        if (runtimeNodeWrapper.exists()) {
            runtimeNodeWrapper.copyTo(termuxNode, overwrite = true)
            termuxNode.setExecutable(true, false)
            // node.real must live next to the wrapper so exec "${SELF_DIR}/node.real" works
            if (runtimeNodeReal.exists()) {
                val termuxNodeReal = File(termuxBin, "node.real")
                if (!termuxNodeReal.exists()) {
                    runtimeNodeReal.copyTo(termuxNodeReal, overwrite = false)
                    termuxNodeReal.setExecutable(true, false)
                }
            }
            Log.i(TAG, "Seeded node from runtime: ${termuxNode.absolutePath}")
        } else if (!termuxNode.exists()) {
            Log.e(TAG, "Runtime node wrapper missing and apt did not install node")
            throw IllegalStateException("Node binary not available — runtime wrapper missing and apt install failed")
        }

        // ── proot-distro ───────────────────────────────────────────────────────────────
        // proot-distro is a plain bash script (~100 KB). Download it from GitHub if apt
        // did not install it. This is the last piece we cannot bundle (size + licensing).
        val termuxProotDistro = File(termuxBin, "proot-distro")
        if (!termuxProotDistro.exists()) {
            Log.i(TAG, "proot-distro not installed by apt — downloading from GitHub")
            try {
                val url = java.net.URL(
                    "https://raw.githubusercontent.com/termux/proot-distro/master/proot-distro.sh"
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout    = 30_000
                conn.connect()
                if (conn.responseCode == 200) {
                    var content = conn.inputStream.readBytes().toString(Charsets.UTF_8)
                    // The GitHub source is a template — replace all @TERMUX_PREFIX@ placeholders
                    // with the actual Termux prefix path so the shebang and internal paths resolve.
                    content = content.replace("@TERMUX_PREFIX@", termuxPrefix.absolutePath)
                    termuxProotDistro.writeText(content, Charsets.UTF_8)
                    termuxProotDistro.setExecutable(true, false)
                    Log.i(TAG, "Downloaded proot-distro (${termuxProotDistro.length()} bytes)")
                } else {
                    throw IOException("HTTP ${conn.responseCode} downloading proot-distro")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download proot-distro: ${e.message}")
                throw IllegalStateException("proot-distro unavailable: apt install failed and download failed — check network", e)
            }
        } else {
            Log.i(TAG, "proot-distro already present at ${termuxProotDistro.absolutePath}")
        }
    }

    private fun ensureDebianInstalled() {
        if (File(debianRoot, "etc").exists()) {
            Log.i(TAG, "Debian is already installed at ${debianRoot.absolutePath}")
            return
        }

        publishProgress("debian-installing", 0, 100)
        // Patch proot-distro trap handlers to prevent rootfs deletion on non-fatal failures.
        // The sed command replaces rm -rf with a no-op (:) only on lines starting with 'trap '.
        val prootDistroPath = File(termuxBin, "proot-distro").absolutePath
        try {
            runTermuxShellCommand(
                "sed -i '/^[[:space:]]*trap /s/rm -rf/: #rm-disabled/g' $prootDistroPath",
                "debian-patch-traps"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to patch proot-distro traps (non-fatal): ${e.message}")
        }
        try {
            runTermuxShellCommand("$prootDistroPath install $DEBIAN_ALIAS", "debian-installing")
        } catch (e: Exception) {
            // proot-distro's post-install step (dpkg-reconfigure) may fail due to
            // Android seccomp blocking certain syscalls. If the rootfs was actually
            // extracted successfully (has /etc), treat as success — we configure
            // the environment ourselves in finalizeDebianEnvironment().
            if (File(debianRoot, "etc").exists()) {
                Log.w(TAG, "proot-distro exited with error but rootfs extraction succeeded: ${e.message}")
            } else {
                throw e
            }
        }
        publishProgress("debian-installing", 100, 100)
    }

    private fun finalizeDebianEnvironment() {
        val rootHome = File(debianRoot, "root")
        rootHome.mkdirs()
        ensureRootGitConfig(rootHome)

        val bashrc = File(rootHome, ".bashrc")
        val marker = "# DevPocket integration"
        val addition = buildString {
            appendLine()
            appendLine(marker)
            appendLine("export PATH=\"/opt/devpocket/bin:\$PATH\"")
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

        // Prevent service start/stop during package installation.
        // When postinst scripts call invoke-rc.d, it checks policy-rc.d first.
        // Exit 101 tells invoke-rc.d the action is not allowed — this is the
        // standard Docker/chroot approach and prevents dbus, systemd, etc.
        // from trying to start system services inside proot (which would fail).
        val policyRcD = File(debianRoot, "usr/sbin/policy-rc.d")
        if (!policyRcD.exists() || !policyRcD.readText().contains("exit 101")) {
            policyRcD.writeText("#!/bin/sh\n# DevPocket: block service starts inside proot\nexit 101\n")
            policyRcD.setExecutable(true, false)
        }

        // Pre-create dbus machine-id files so dbus-uuidgen --ensure doesn't fail.
        // dbus postinst requires these files to exist even when dbus isn't running.
        val machineIdFile = File(debianRoot, "etc/machine-id")
        if (!machineIdFile.exists() || machineIdFile.length() == 0L) {
            machineIdFile.parentFile?.mkdirs()
            machineIdFile.writeText("devpocket00000000000000000000001\n")
        }
        File(debianRoot, "var/lib/dbus").mkdirs()
        val dbusIdFile = File(debianRoot, "var/lib/dbus/machine-id")
        if (!dbusIdFile.exists() || dbusIdFile.length() == 0L) {
            dbusIdFile.writeText("devpocket00000000000000000000001\n")
        }
        // /run/dbus is normally a tmpfs mount; pre-create it so dbus-daemon socket
        // path exists (dbus postinst checks the dir, not the socket itself).
        File(debianRoot, "run/dbus").mkdirs()

        // Install apt config for proot compatibility:
        // - APT::Sandbox::User "root" : apt's HTTP method can't setresuid() inside proot
        val aptConfDir = File(debianRoot, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        val aptProotConf = File(aptConfDir, "99-devpocket-proot")
        aptProotConf.writeText("APT::Sandbox::User \"root\";\n")

        // Replace dpkg-preconfigure with a no-op.
        // dpkg-preconfigure forks children to run debconf config scripts; those
        // children fail with ENOSYS inside Android proot (Android kernel restricts
        // ptrace on grandchildren). Packages still install correctly — debconf
        // just uses built-in defaults for all questions.
        val dpkgPreconf = File(debianRoot, "usr/sbin/dpkg-preconfigure")
        dpkgPreconf.parentFile?.mkdirs()
        val noopScript = "#!/bin/sh\n# DevPocket: disabled — fork/exec in proot fails with ENOSYS\nexit 0\n"
        if (!dpkgPreconf.exists() || !dpkgPreconf.readText().contains("DevPocket")) {
            dpkgPreconf.writeText(noopScript)
            dpkgPreconf.setExecutable(true, false)
        }

        // Pre-create the _ssh group needed by openssh-client postinst (groupadd
        // uses link() for lock files — nlink check fails with our shim)
        val groupFile = File(debianRoot, "etc/group")
        if (groupFile.exists() && !groupFile.readText().contains("_ssh")) {
            groupFile.appendText("_ssh:x:101:\n")
        }

        // Install link() shim — needed so dpkg can create status-old backup
        // (Android blocks link() with EPERM in app private dirs)
        val shimSrc = File(TheiaRuntimePaths.runtimeRoot(context), "bin/link_shim.so")
        val shimDir = File(debianRoot, "usr/lib/devpocket")
        shimDir.mkdirs()
        val shimDst = File(shimDir, "link_shim.so")
        if (shimSrc.exists()) {
            shimSrc.copyTo(shimDst, overwrite = true)
            shimDst.setReadable(true, false)
        }

        // System-wide preload: ensures link_shim.so is loaded for ALL processes
        // (dpkg, dpkg-deb, apt helpers) even when LD_PRELOAD is not in the env chain.
        val ldSoPreload = File(debianRoot, "etc/ld.so.preload")
        val shimGuestPath = "/usr/lib/devpocket/link_shim.so"
        if (!ldSoPreload.exists() || !ldSoPreload.readText().trim().contains(shimGuestPath)) {
            ldSoPreload.writeText("$shimGuestPath\n")
        }

        // Clean up stale 0-byte alternative files left by a failed dpkg-reconfigure
        // during the initial proot-distro install. update-alternatives calls statx()
        // on these and gets EINVAL (proot returns EINVAL for 0-byte regular files),
        // which causes postinst scripts to fail. Deleting them lets update-alternatives
        // recreate them as proper symlinks.
        val altDir = File(debianRoot, "etc/alternatives")
        val altAdminDir = File(debianRoot, "var/lib/dpkg/alternatives")
        altDir.listFiles()?.forEach { altFile ->
            if (altFile.isFile && altFile.length() == 0L) {
                val adminRecord = File(altAdminDir, altFile.name)
                adminRecord.delete()
                altFile.delete()
                Log.d(TAG, "Removed stale 0-byte alternative: ${altFile.name}")
            }
        }

        writeWrapperScripts()

        // Hold (and fake-configure) core packages BEFORE dpkg --configure --pending.
        // dbus and related packages have postinst scripts that fail with ENOSYS
        // (chdir('/') fails inside proot) — we mark them as "hold ok installed"
        // directly in the status database, which both holds them from future upgrades
        // AND makes dpkg consider them fully configured, so --configure --pending
        // skips them entirely.
        holdDebianCorePackages()

        // Configure any remaining packages left in half-configured state.
        // dpkg skips held packages, so only safe-to-configure packages run here.
        runDebianProotCommand(
            listOf("/usr/bin/dpkg", "--configure", "--pending"),
            "debian-configure-pending",
            tolerateFailure = true
        )

        ensureDebianGitAvailable()

        Log.i(TAG, "Finalized Debian root environment at ${rootHome.absolutePath}")
    }

    /**
     * Mark core Debian packages as "hold" so apt never tries to upgrade them.
     * dpkg upgrading itself inside proot exits 100; libc6 replacement while in use
     * is unsafe. We write directly to /var/lib/dpkg/status rather than running
     * apt-mark (which requires apt to be fully functional first).
     */
    private fun holdDebianCorePackages() {
        val statusFile = File(debianRoot, "var/lib/dpkg/status")
        if (!statusFile.exists()) return

        val packagesToHold = setOf("dpkg", "libc6", "libc-bin", "libc-l10n", "locales",
            "dbus", "dbus-bin", "dbus-daemon", "dbus-session-bus-common", "dbus-system-bus-common")
        val lines = statusFile.readLines(Charsets.UTF_8)
        val result = mutableListOf<String>()
        var currentPkg: String? = null
        var changed = false

        for (line in lines) {
            if (line.startsWith("Package: ")) {
                currentPkg = line.removePrefix("Package: ").trim()
            }
            if (currentPkg != null && currentPkg in packagesToHold &&
                line.startsWith("Status: ") &&
                (line.contains("installed") || line.contains("half-configured") ||
                    line.contains("unpacked"))
            ) {
                // Set to "hold ok installed" regardless of current state:
                // - "install ok installed"      → hold (prevent future upgrade)
                // - "install ok half-configured" → hold + fake-configure (skip broken postinst)
                // - "install ok unpacked"        → hold + fake-configure
                // dbus postinst fails with chdir('/') ENOSYS inside Android proot.
                result.add("Status: hold ok installed")
                Log.i(TAG, "Marked Debian package '$currentPkg' as hold ok installed (was: $line)")
                changed = true
            } else {
                result.add(line)
            }
        }

        if (changed) {
            // Preserve trailing newline — dpkg requires the status file to end with \n.
            // joinToString("\n") omits it, causing dpkg to reject the last package entry.
            statusFile.writeText(result.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    private fun ensureRootGitConfig(rootHome: File) {
        val gitConfig = File(rootHome, ".gitconfig")
        if (!gitConfig.exists()) {
            gitConfig.parentFile?.mkdirs()
            gitConfig.writeText(ROOT_GIT_CONFIG_PLACEHOLDER)
        }
    }

    private fun hasDebianGitBinary(rootFs: File): Boolean = File(rootFs, DEBIAN_GIT_BINARY_PATH).exists()

    private fun ensureDebianGitAvailable() {
        if (hasDebianGitBinary(debianRoot)) {
            Log.i(TAG, "Debian git already present at ${File(debianRoot, DEBIAN_GIT_BINARY_PATH).absolutePath}")
            return
        }

        Log.i(TAG, "Installing Debian git package for IDE terminal support")
        runDebianShellWrapperCommand("apt-get update", "debian-apt-update")
        runDebianShellWrapperCommand("apt-get install -y $DEBIAN_GIT_PACKAGE", "debian-install-git")

        if (!hasDebianGitBinary(debianRoot)) {
            throw IllegalStateException("Debian git installation completed but $DEBIAN_GIT_BINARY_PATH is still missing")
        }
    }

    private fun runDebianShellWrapperCommand(command: String, phase: String) {
        Log.i(TAG, "Running $phase through shell wrapper: $command")

        val builder = ProcessBuilder(listOf(termuxShellWrapper.absolutePath, "-lc", command))
        builder.directory(context.filesDir.parentFile ?: context.filesDir)
        builder.redirectErrorStream(true)
        builder.environment().remove("PWD")

        val process = builder.start()
        rememberCommandOutput("[$phase] $ $command")
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.i(TAG, "[$phase] $line")
                rememberCommandOutput("[$phase] $line")
            }
        }

        val exitCode = process.waitFor()
        Log.i(TAG, "$phase exited with code $exitCode")
        if (exitCode != 0) {
            throw IllegalStateException("$phase failed with exit code $exitCode\n${recentDiagnosticsSummary()}")
        }
    }

    /**
     * Run a command directly inside the Debian proot (not through devpocket-shell).
     * Used during finalizeDebianEnvironment() before devpocket-shell is fully ready.
     */
    private fun runDebianProotCommand(
        guestCommand: List<String>,
        phase: String,
        tolerateFailure: Boolean = false
    ) {
        val prootBin = File(termuxBin, "proot")
        if (!prootBin.exists()) {
            Log.w(TAG, "$phase: proot binary missing, skipping")
            return
        }
        val shimPath = "/usr/lib/devpocket/link_shim.so"
        val runtimeRoot = TheiaRuntimePaths.runtimeRoot(context)

        val command = mutableListOf(
            prootBin.absolutePath,
            "--kill-on-exit", "-0", "-r", debianRoot.absolutePath,
            "-b", "/proc:/proc", "-b", "/sys:/sys", "-b", "/dev:/dev",
            "-b", "${runtimeRoot.absolutePath}:/opt/devpocket",
            "/usr/bin/env",
            "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm-256color",
            "DEBIAN_FRONTEND=noninteractive", "DEBCONF_NONINTERACTIVE_SEEN=true",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_PRELOAD=$shimPath",
            "LD_LIBRARY_PATH="
        ) + guestCommand

        Log.i(TAG, "Running $phase in Debian proot: ${guestCommand.joinToString(" ")}")
        try {
            val builder = ProcessBuilder(command)
            builder.environment()["PROOT_NO_SECCOMP"] = "1"
            builder.environment()["PROOT_TMP_DIR"] = termuxTmp.absolutePath
            builder.environment()["LD_LIBRARY_PATH"] = termuxLib.absolutePath
            builder.redirectErrorStream(true)

            val process = builder.start()
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.i(TAG, "[$phase] $line")
                    rememberCommandOutput("[$phase] $line")
                }
            }
            val exitCode = process.waitFor()
            Log.i(TAG, "$phase exited with code $exitCode")
            if (exitCode != 0 && !tolerateFailure) {
                throw IllegalStateException("$phase failed with exit code $exitCode\n${recentDiagnosticsSummary()}")
            }
        } catch (e: Exception) {
            if (tolerateFailure) {
                Log.w(TAG, "$phase failed (tolerated): ${e.message}")
            } else {
                throw e
            }
        }
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
            appendLine("export PROOT_TMP_DIR=\"${termuxTmp.absolutePath}\"")
            appendLine("export PROOT_NO_SECCOMP=1")
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
            appendLine("COMPAT_ROOTFS=\"${termuxCompatCache.absolutePath}/rootfs\"")
            appendLine("mkdir -p \"${termuxCompatCache.absolutePath}\" \"${termuxHome.absolutePath}\" \"${termuxHome.absolutePath}/.termux\" \"${termuxTmp.absolutePath}\" 2>/dev/null")
            appendLine("export PROOT_TMP_DIR=\"${termuxCompatCache.absolutePath}\"")
            appendLine("export PROOT_NO_SECCOMP=1")
            appendLine("if [ ! -x \"\${PROOT_BIN}\" ]; then")
            appendLine("  echo \"DevPocket error: missing bundled compatibility proot at \${PROOT_BIN}\" >&2")
            appendLine("  exit 127")
            appendLine("fi")
            // Create a minimal rootfs skeleton so proot can find bind mount destinations.
            // proot with -r / fails because /data/data/com.termux doesn't exist on the real
            // Android filesystem (our app is com.theia.mobile). By using a custom rootfs
            // with the expected directory structure, proot can properly set up bind mounts.
            appendLine("mkdir -p \"\${COMPAT_ROOTFS}/data/data/com.termux/files\" 2>/dev/null")
            appendLine("mkdir -p \"\${COMPAT_ROOTFS}/data/data/com.termux/cache\" 2>/dev/null")
            appendLine("mkdir -p \"\${COMPAT_ROOTFS}${context.filesDir.absolutePath}\" 2>/dev/null")
            appendLine("mkdir -p \"\${COMPAT_ROOTFS}${context.cacheDir.absolutePath}\" 2>/dev/null")
            // Set all env vars BEFORE proot exec — proot inherits them from the parent process.
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
            appendLine("if [ -f \"\${HOST_PREFIX}/etc/tls/cert.pem\" ]; then")
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
            appendLine("if [ -n \"\${DPKG_ADMINDIR}\" ]; then export DPKG_ADMINDIR DPKG_FORCE; fi")
            appendLine("echo \"DevPocket: host Termux compatibility mode (launcher=\${PROOT_BIN} rootfs=\${COMPAT_ROOTFS} cmd=\$*)\" >&2")
            appendLine("env > \"/data/user/0/com.theia.mobile/files/app-env.log\"")
            // Use the custom rootfs with pre-created directory skeleton, bind real content on top.
            // Removed --link2symlink because internal EXT4/F2FS supports hardlinks,
            // and --link2symlink causes ENOSYS (Function not implemented) in proot for some syscalls.
            appendLine("exec \"\${PROOT_BIN}\" --kill-on-exit -r \"\${COMPAT_ROOTFS}\" \\")
            appendLine("  -b /system -b /apex -b /dev -b /proc -b /sys -b /sdcard -b /storage \\")
            appendLine("  -b \"${context.filesDir.absolutePath}:\${LEGACY_FILES}\" \\")
            appendLine("  -b \"${context.cacheDir.absolutePath}:\${LEGACY_CACHE}\" \\")
            appendLine("  -b \"${context.filesDir.absolutePath}:${context.filesDir.absolutePath}\" \\")
            appendLine("  -b \"${context.cacheDir.absolutePath}:${context.cacheDir.absolutePath}\" \\")
            appendLine("  -w \"\${LEGACY_HOME}\" \\")
            appendLine("  \"\$@\"")
        }
        termuxCompatWrapper.writeText(compatWrapper)
        termuxCompatWrapper.setExecutable(true, false)

        val shellWrapper = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("APP_FILES=\"${context.filesDir.absolutePath}\"")
            appendLine("APP_DATA_DIR=\"${context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath}\"")
            appendLine("APP_FILES_ALIAS=\"\${APP_FILES}\"")
            appendLine("case \"\${APP_FILES}\" in")
            appendLine("  /data/user/0/*) APP_FILES_ALIAS=\"/data/data/\${APP_FILES#/data/user/0/}\" ;;")
            appendLine("esac")
            appendLine("PREFIX=\"${termuxPrefix.absolutePath}\"")
            appendLine("TERMUX_ENV=\"${termuxEnvWrapper.absolutePath}\"")
            appendLine("TERMUX_COMPAT=\"${termuxCompatWrapper.absolutePath}\"")
            appendLine("LEGACY_PROOT_DISTRO=\"$LEGACY_TERMUX_PREFIX/bin/proot-distro\"")
            appendLine("DEBIAN_ROOT=\"${debianRoot.absolutePath}\"")
            appendLine("RUNTIME_ROOT=\"${TheiaRuntimePaths.runtimeRoot(context).absolutePath}\"")
            appendLine("CONFIG_DIR=\"${TheiaRuntimePaths.configDir(context).absolutePath}\"")
            appendLine("EXTENSIONS_DIR=\"${TheiaRuntimePaths.extensionsRoot(context).absolutePath}\"")
            appendLine("if [ ! -x \"${termuxCompatWrapper.absolutePath}\" ]; then")
            appendLine("  echo \"DevPocket error: missing Termux compat wrapper at ${termuxCompatWrapper.absolutePath}\" >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("if [ ! -d \"${debianRoot.absolutePath}\" ]; then")
            appendLine("  echo \"DevPocket error: Debian rootfs is not installed yet\" >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("unset PWD")
            appendLine("HOST_PWD_ORIGINAL=\$(/system/bin/pwd -P 2>/dev/null || pwd -P 2>/dev/null || pwd 2>/dev/null || printf '%s' \"\${APP_DATA_DIR}\")")
            appendLine("HOST_PWD=\"\${HOST_PWD_ORIGINAL}\"")
            appendLine("case \"\${HOST_PWD_ORIGINAL}\" in")
            appendLine("  \"${termuxHome.absolutePath}\"|\"${termuxHome.absolutePath}\"/*|\"\${APP_FILES_ALIAS}/home\"|\"\${APP_FILES_ALIAS}/home\"/*)")
            appendLine("    if cd \"\${APP_DATA_DIR}\" 2>/dev/null; then")
            appendLine("      HOST_PWD=\$(/system/bin/pwd -P 2>/dev/null || pwd -P 2>/dev/null || pwd 2>/dev/null || printf '%s' \"\${APP_DATA_DIR}\")")
            appendLine("    else")
            appendLine("      HOST_PWD=\"\${APP_DATA_DIR}\"")
            appendLine("    fi")
            appendLine("    ;;")
            appendLine("esac")
            appendLine("GUEST_WD=\"/root\"")
            appendLine("case \"\${HOST_PWD}\" in")
            appendLine("  \"${debianRoot.absolutePath}\") GUEST_WD=\"/\" ;;")
            appendLine("  \"${debianRoot.absolutePath}\"/*) GUEST_WD=\"/\${HOST_PWD#${debianRoot.absolutePath}/}\" ;;")
            appendLine("  /sdcard|/sdcard/*|/storage|/storage/*) GUEST_WD=\"\${HOST_PWD}\" ;;")
            appendLine("esac")
            appendLine("case \" \${*} \" in")
            appendLine("  *\\ --login\\ *|*\\ -l\\ *) ;;")
            appendLine("  *) set -- -l \"\${@}\" ;;")
            appendLine("esac")
            // Build LD_PRELOAD: link_shim.so (fixes dpkg link() EPERM on Android)
            // + proot-getcwd.so (fixes getcwd ENOSYS inside proot)
            appendLine("LINK_SHIM=\"/usr/lib/devpocket/link_shim.so\"")
            appendLine("PROOT_GETCWD_PRELOAD=\"\"")
            appendLine("if [ -f \"\${RUNTIME_ROOT}/bin/proot-getcwd.so\" ]; then")
            appendLine("  PROOT_GETCWD_PRELOAD=\"/opt/devpocket/bin/proot-getcwd.so\"")
            appendLine("fi")
            appendLine("if [ -n \"\${PROOT_GETCWD_PRELOAD}\" ]; then")
            appendLine("  PRELOAD=\"\${LINK_SHIM}:\${PROOT_GETCWD_PRELOAD}\"")
            appendLine("else")
            appendLine("  PRELOAD=\"\${LINK_SHIM}\"")
            appendLine("fi")
            // Run Termux proot directly — no compat wrapper, no nesting.
            // Host LD_LIBRARY_PATH lets Termux proot find libtalloc; it is cleared
            // inside the guest (LD_LIBRARY_PATH=) so Debian programs use their own libs.
            // /apex bind: needed for Android binaries (e.g. node.real) run inside Debian.
            // -0 makes proot pretend UID=0 so dpkg/apt work correctly.
            appendLine("echo \"DevPocket: entering Debian via Termux proot directly (host-pwd=\${HOST_PWD} guest-pwd=\${GUEST_WD})\" >&2")
            appendLine("export PROOT_NO_SECCOMP=1")
            appendLine("export PROOT_TMP_DIR=\"${termuxTmp.absolutePath}\"")
            appendLine("export LD_LIBRARY_PATH=\"${termuxLib.absolutePath}\"")
            appendLine("mkdir -p \"\${PROOT_TMP_DIR}\" 2>/dev/null")
            // Build optional bind mounts (only if path exists on host)
            appendLine("APEX_BIND=\"\"")
            appendLine("if [ -d /apex ]; then APEX_BIND=\"-b /apex:/apex\"; fi")
            appendLine("VENDOR_BIND=\"\"")
            appendLine("if [ -d /vendor ]; then VENDOR_BIND=\"-b /vendor:/vendor\"; fi")
            appendLine("exec \"${termuxBin.absolutePath}/proot\" \\")
            appendLine("  --kill-on-exit -0 -r \"\${DEBIAN_ROOT}\" \\")
            appendLine("  -w \"\${GUEST_WD}\" \\")
            appendLine("  -b /proc:/proc -b /sys:/sys -b /dev:/dev -b /dev/pts:/dev/pts \\")
            appendLine("  -b /sdcard:/sdcard -b /storage:/storage -b /system:/system \\")
            appendLine("  \${APEX_BIND} \${VENDOR_BIND} \\")
            appendLine("  -b \"\${RUNTIME_ROOT}:/opt/devpocket\" \\")
            appendLine("  -b \"\${CONFIG_DIR}:/opt/devpocket-config\" \\")
            appendLine("  -b \"\${EXTENSIONS_DIR}:/opt/devpocket-extensions\" \\")
            appendLine("  /usr/bin/env \\")
            appendLine("  HOME=/root USER=root LOGNAME=root TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8 LC_ALL=C.UTF-8 ANDROID_STORAGE=/sdcard \\")
            appendLine("  DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=$ROOT_GIT_CONFIG_PATH \\")
            appendLine("  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/devpocket/bin \\")
            appendLine("  LD_LIBRARY_PATH= \\")
            appendLine("  SSL_CERT_FILE=/opt/devpocket/etc/ca-certificates/cacert.pem GIT_SSL_CAINFO=/opt/devpocket/etc/ca-certificates/cacert.pem CURL_CA_BUNDLE=/opt/devpocket/etc/ca-certificates/cacert.pem NODE_EXTRA_CA_CERTS=/opt/devpocket/etc/ca-certificates/cacert.pem LD_PRELOAD=\"\${PRELOAD}\" \\")
            appendLine("  /bin/bash \"\${@}\"")
        }
        termuxShellWrapper.writeText(shellWrapper)
        termuxShellWrapper.setExecutable(true, false)
        Log.i(TAG, "Wrote Termux wrappers at ${termuxEnvWrapper.absolutePath}, ${termuxCompatWrapper.absolutePath}, and ${termuxShellWrapper.absolutePath}")
    }

    private fun termuxDpkgEnv(): Map<String, String> = mapOf(
        // NOTE: Do NOT set DPKG_ROOT here. Termux .deb packages contain absolute
        // paths (e.g. ./data/data/com.termux/files/usr/bin/proot) inside their
        // archives. Setting DPKG_ROOT causes dpkg to prepend the root prefix
        // to these already-absolute paths, creating invalid doubled paths and
        // causing dpkg to fail with exit code 100.
        "DPKG_ADMINDIR" to "$LEGACY_TERMUX_PREFIX/var/lib/dpkg",
        "DPKG_FORCE" to "all"
    )

    /** DPKG env vars using the real app-prefix paths (for direct execution without proot). */
    private fun termuxDpkgEnvLocal(): Map<String, String> = mapOf(
        // NOTE: Do NOT set DPKG_ROOT — see termuxDpkgEnv() comment above.
        "DPKG_ADMINDIR" to "${termuxPrefix.absolutePath}/var/lib/dpkg",
        "DPKG_FORCE" to "all"
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
        val mergedEnv = extraEnv + mapOf(
            "TMPDIR" to termuxTmp.absolutePath,
            "PROOT_NO_SECCOMP" to "1"
        )
        runTermuxCommand(listOf(File(termuxBin, "bash").absolutePath, "-lc", command), phase, mergedEnv)
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
