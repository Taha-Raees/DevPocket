package com.theia.mobile

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Manages bootstrap runtime installation:
 * - Downloads Debian rootfs from remote source
 * - Verifies checksums
 * - Extracts to app-private Linux workspace
 * - Creates installation manifest
 * - Supports resume and cleanup
 */
class BootstrapInstallerService(private val context: Context) {
    companion object {
        private const val TAG = "BootstrapInstaller"
        
        // Debian rootfs metadata (update these as new versions are released)
        const val ROOTFS_VERSION = "debian-userland-2026-03-31"
        const val ROOTFS_FILENAME = "arm64-rootfs.tar.gz"
        const val ROOTFS_SIZE_MB = 150
        const val ROOTFS_URL = "https://github.com/CypherpunkArmory/UserLAnd-Assets-Debian/releases/download/v0.0.10/${ROOTFS_FILENAME}"
        const val ROOTFS_SHA256_URL = "${ROOTFS_URL}.sha256"
        const val ROOTFS_SHA256 = "47eb42fd93d27b4dd0520a1228c44431aa0cdff9a93ba624039147f984b79028"
        
        const val BOOTSTRAP_VERSION = "1.0.0"
    }

    data class InstallProgress(
        val phase: String,           // "downloading", "verifying", "extracting", "finalizing"
        val currentBytes: Long,
        val totalBytes: Long,
        val percentComplete: Int
    )

    data class InstallManifest(
        val version: String = BOOTSTRAP_VERSION,
        val rootfsVersion: String = ROOTFS_VERSION,
        val installedAt: Long = System.currentTimeMillis(),
        val rootfsPath: String = "",
        val checksum: String = "",
        val extractedSuccessfully: Boolean = false
    )

    private val devpocketBase = File(context.filesDir, "linux")
    private val bootstrapDir = File(devpocketBase, "bootstrap")
    private val debianDir = File(devpocketBase, "debian")
    private val tempDir = File(devpocketBase, "temp")
    private val manifestFile = File(devpocketBase, "INSTALL_MANIFEST.json")
    
    private val stateManager = OnboardingStateManager(context)
    private var progressCallback: ((InstallProgress) -> Unit)? = null

    fun setProgressCallback(callback: (InstallProgress) -> Unit) {
        this.progressCallback = callback
    }

    /**
     * Perform complete installation flow:
     * 1. Verify preconditions
     * 2. Download rootfs
     * 3. Verify checksum
     * 4. Extract
     * 5. Create user account
     * 6. Write manifest
     */
    fun install(): Boolean {
        try {
            Log.i(TAG, "Starting bootstrap installation")
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)

            // Ensure directories exist
            devpocketBase.mkdirs()
            tempDir.mkdirs()

            // Phase 1: Download Debian rootfs
            publishProgress("downloading", 0, ROOTFS_SIZE_MB.toLong() * 1024 * 1024)
            if (!downloadRootfs()) {
                stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED)
                stateManager.setInstallError("Failed to download Debian rootfs")
                Log.e(TAG, "Download failed")
                return false
            }

            // Phase 2: Verify checksum
            publishProgress("verifying", 0, ROOTFS_SIZE_MB.toLong() * 1024 * 1024)
            val rootfsFile = File(tempDir, ROOTFS_FILENAME)
            val checksumFile = downloadChecksumFile()
            val verificationResult = SecurityManager.verifyRootfsSignature(rootfsFile, checksumFile)
            if (!verificationResult.verified) {
                stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED)
                stateManager.setInstallError(verificationResult.errorMessage.ifBlank { "Checksum verification failed" })
                Log.e(TAG, "Checksum mismatch")
                rootfsFile.delete()
                checksumFile.delete()
                return false
            }
            val calculatedChecksum = verificationResult.checksum
            Log.i(TAG, "Checksum verified: $calculatedChecksum")

            // Phase 3: Extract rootfs
            publishProgress("extracting", 0, ROOTFS_SIZE_MB.toLong() * 1024 * 1024)
            debianDir.mkdirs()
            if (!extractTarGz(rootfsFile, debianDir)) {
                stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED)
                stateManager.setInstallError("Failed to extract rootfs")
                Log.e(TAG, "Extraction failed")
                return false
            }
            Log.i(TAG, "Rootfs extracted successfully")

            // Phase 4: Create user account (will be refined in Phase 6)
            publishProgress("finalizing", 0, 100)
            val config = stateManager.getConfig()
            val username = config.username ?: "devpocket"
            if (!createUserAccount(username)) {
                Log.e(TAG, "Failed to create user account")
                // Don't fail completely; account creation can be retried
            }

            // Phase 5: Write installation manifest
            val manifest = InstallManifest(
                rootfsPath = debianDir.absolutePath,
                checksum = calculatedChecksum,
                extractedSuccessfully = true
            )
            writeManifest(manifest)

            // Mark installation complete
            stateManager.setRootfsVersion(ROOTFS_VERSION)
            stateManager.completeOnboarding()
            stateManager.setInstallProgress(100)

            Log.i(TAG, "Bootstrap installation completed successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Installation error: ${e.message}", e)
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED)
            stateManager.setInstallError("Installation error: ${e.message}")
            return false
        }
    }

    private fun downloadRootfs(): Boolean {
        return try {
            val rootfsFile = File(tempDir, ROOTFS_FILENAME)
            
            // If file already exists with reasonable size, skip download
            if (rootfsFile.exists() && rootfsFile.length() > ROOTFS_SIZE_MB * 1024 * 1024 * 0.8) {
                Log.i(TAG, "Rootfs file already exists with sufficient size, skipping download")
                return true
            }

            Log.i(TAG, "Downloading rootfs from $ROOTFS_URL")
            val url = java.net.URL(ROOTFS_URL)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.connect()
            
            if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed with response code: ${conn.responseCode}")
                return false
            }
            
            val totalBytes = conn.contentLength.toLong()
            var downloadedBytes = 0L
            
            conn.inputStream.use { input ->
                rootfsFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        // Publish progress
                        val percent = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0
                        publishProgress("downloading", downloadedBytes, totalBytes)
                    }
                }
            }
            
            Log.i(TAG, "Download complete: $downloadedBytes bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            false
        }
    }

    private fun downloadChecksumFile(): File {
        val checksumFile = File(tempDir, "$ROOTFS_FILENAME.sha256")
        if (ROOTFS_SHA256.isNotBlank()) {
            checksumFile.writeText("$ROOTFS_SHA256  $ROOTFS_FILENAME\n")
            return checksumFile
        }

        val connection = (java.net.URL(ROOTFS_SHA256_URL).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
        }

        connection.connect()
        if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Failed to download checksum file: HTTP ${connection.responseCode}")
        }

        connection.inputStream.use { input ->
            checksumFile.outputStream().use { output -> input.copyTo(output) }
        }

        return checksumFile
    }

    private fun calculateSHA256(file: File): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int

        FileInputStream(file).use { fis ->
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                messageDigest.update(buffer, 0, bytesRead)
            }
        }

        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File): Boolean {
        return try {
            Log.i(TAG, "Extracting ${tarGzFile.absolutePath} to ${destDir.absolutePath}")
            
            destDir.mkdirs()
            
            FileInputStream(tarGzFile).use { fileInput ->
                val archiveInput = when {
                    tarGzFile.name.endsWith(".tar.gz") || tarGzFile.name.endsWith(".tgz") -> {
                        TarArchiveInputStream(GzipCompressorInputStream(fileInput))
                    }
                    tarGzFile.name.endsWith(".tar.xz") || tarGzFile.name.endsWith(".txz") -> {
                        TarArchiveInputStream(XZCompressorInputStream(fileInput))
                    }
                    tarGzFile.name.endsWith(".tar") -> TarArchiveInputStream(fileInput)
                    else -> throw IllegalArgumentException("Unsupported archive format: ${tarGzFile.name}")
                }

                archiveInput.use { tarInput ->
                    var entry = tarInput.nextTarEntry
                    while (entry != null) {
                        writeTarEntry(destDir, tarInput, entry)
                        entry = tarInput.nextTarEntry
                    }
                }
            }
            
            // Verify extraction
            if (!File(destDir, "bin").exists() || !File(destDir, "etc").exists()) {
                Log.e(TAG, "Extraction verification failed: required directories missing")
                return false
            }
            
            // Copy shell wrapper into extracted Debian
            try {
                copyShellWrapperToDebianBin(destDir)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy shell wrapper: ${e.message}")
                // Don't fail completely; wrapper can be added later
            }
            
            Log.i(TAG, "Extraction complete and verified")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            false
        }
    }

    private fun writeTarEntry(destDir: File, tarInput: TarArchiveInputStream, entry: TarArchiveEntry) {
        val sanitizedName = entry.name.removePrefix("./").removePrefix("/")
        if (sanitizedName.isBlank()) {
            return
        }

        val output = File(destDir, sanitizedName)
        val canonicalDest = destDir.canonicalPath + File.separator
        val canonicalOutput = output.canonicalPath
        require(canonicalOutput.startsWith(canonicalDest)) { "Blocked path traversal for ${entry.name}" }

        when {
            entry.isDirectory -> output.mkdirs()
            entry.isSymbolicLink -> {
                output.parentFile?.mkdirs()
                if (output.exists()) {
                    output.delete()
                }

                try {
                    Os.symlink(entry.linkName, output.absolutePath)
                } catch (errno: ErrnoException) {
                    throw IllegalStateException("Failed to create symlink ${entry.name} -> ${entry.linkName}: ${errno.message}", errno)
                }
            }
            else -> {
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { outputStream ->
                    tarInput.copyTo(outputStream)
                }
                output.setReadable(true, false)
                output.setWritable(true, true)
                if ((entry.mode and 0b001_001_001) != 0) {
                    output.setExecutable(true, false)
                }
            }
        }
    }

    private fun createUserAccount(username: String): Boolean {
        return try {
            Log.i(TAG, "Creating Linux user account: $username")
            
            val config = stateManager.getConfig()
            val sudoMode = config.sudoMode ?: "passwordless"
            val sudoPassword = config.sudoPassword
            
            // 1. Create user home directory
            val userHome = File(debianDir, "home/$username")
            userHome.mkdirs()
            
            // 2. Initialize home for a real Linux user
            // Write /etc/passwd entry
            val etcDir = File(debianDir, "etc")
            etcDir.mkdirs()
            
            val passwdEntry = "$username:x:1000:1000:DevPocket User:/home/$username:/bin/bash"
            val passwdFile = File(etcDir, "passwd")
            if (passwdFile.exists()) {
                // Remove old entry if exists
                val lines = passwdFile.readLines().filter { !it.startsWith("$username:") }
                passwdFile.writeText((lines + passwdEntry).joinToString("\n") + "\n")
            } else {
                passwdFile.writeText(passwdEntry + "\n")
            }
            
            // Write /etc/group entry
            val groupEntry = "$username:x:1000:"
            val groupFile = File(etcDir, "group")
            if (groupFile.exists()) {
                val lines = groupFile.readLines().filter { !it.startsWith("$username:") }
                groupFile.writeText((lines + groupEntry).joinToString("\n") + "\n")
            } else {
                groupFile.writeText(groupEntry + "\n")
            }
            
            // 3. Create shell configuration files
            val bashrcContent = """
                # .bashrc for DevPocket Debian environment
                
                # Colors
                export LS_COLORS='di=34:ln=35:so=32:pi=33:ex=31:bd=46;34:cd=43;34:su=41;37:sg=46;37:tw=42;37:ow=43;37'
                
                # Aliases
                alias ls='ls --color=auto'
                alias ll='ls -la --color=auto'
                alias grep='grep --color=auto'
                alias rm='rm -i'
                alias cp='cp -i'
                alias mv='mv -i'
                
                # Prompt with username and path
                export PS1='\[\033[01;32m\]\u@devpocket\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                
                # History settings
                export HISTCONTROL=ignoredups:ignorespace
                export HISTSIZE=10000
                export HISTFILESIZE=20000
                
                # Terminal
                export TERM=xterm-256color
                export COLORTERM=truecolor
                export LC_ALL=C.UTF-8
                export LANG=C.UTF-8
            """.trimIndent()
            
            val bashrcFile = File(userHome, ".bashrc")
            bashrcFile.writeText(bashrcContent)
            bashrcFile.setReadable(true, true)
            bashrcFile.setWritable(true, true)
            
            // Write .bash_profile
            val bashProfileContent = """
                # .bash_profile
                if [ -f ~/.bashrc ]; then
                    source ~/.bashrc
                fi
                export PATH="/home/$username/.local/bin:${'$'}PATH"
            """.trimIndent()
            
            val profileFile = File(userHome, ".bash_profile")
            profileFile.writeText(bashProfileContent)
            profileFile.setReadable(true, true)
            profileFile.setWritable(true, true)
            
            // 4. Create .ssh directory (for future SSH key support)
            val sshDir = File(userHome, ".ssh")
            sshDir.mkdirs()
            sshDir.setReadable(true, true)
            sshDir.setWritable(true, true)
            sshDir.setExecutable(true, true)
            
            // 5. Write sudoers configuration
            val sudoersDir = File(debianDir, "etc/sudoers.d")
            sudoersDir.mkdirs()
            
            val sudoersContent = if (sudoMode == "passwordless") {
                "# DevPocket passwordless sudo\n$username ALL=(ALL) NOPASSWD:ALL\n"
            } else {
                "# DevPocket password-protected sudo\n$username ALL=(ALL) ALL\n"
            }
            
            val sudoersFile = File(sudoersDir, "devpocket-$username")
            sudoersFile.writeText(sudoersContent)
            sudoersFile.setReadable(true, true)
            sudoersFile.setWritable(false, true)
            sudoersFile.setExecutable(false, false)
            
            // Set user home directory ownership/permissions (simulate Linux ownership)
            // Note: On Android's app-private filesystem, ownership is limited, but we can set permissions
            setOwnableDirectory(userHome, true)
            
            Log.i(TAG, "User account created successfully: $username")
            Log.i(TAG, "  Home directory: ${userHome.absolutePath}")
            Log.i(TAG, "  Sudo mode: $sudoMode")
            Log.i(TAG, "  Shell: /bin/bash")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "User account creation failed: ${e.message}", e)
            false
        }
    }
    
    private fun setOwnableDirectory(dir: File, executable: Boolean) {
        try {
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            if (executable) {
                dir.setExecutable(true, true)
            }
            
            // Recursively set permissions on all subdirectories
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    child.setReadable(true, true)
                    child.setWritable(true, true)
                    child.setExecutable(true, true)
                    setOwnableDirectory(child, true)
                } else {
                    child.setReadable(true, true)
                    child.setWritable(true, true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set directory permissions: ${e.message}")
        }
    }

    private fun writeManifest(manifest: InstallManifest) {
        try {
            // Serialize manifest to JSON and write to file
            val json = buildString {
                append("{\n")
                append("  \"version\": \"${manifest.version}\",\n")
                append("  \"rootfsVersion\": \"${manifest.rootfsVersion}\",\n")
                append("  \"installedAt\": ${manifest.installedAt},\n")
                append("  \"rootfsPath\": \"${manifest.rootfsPath}\",\n")
                append("  \"checksum\": \"${manifest.checksum}\",\n")
                append("  \"extractedSuccessfully\": ${manifest.extractedSuccessfully}\n")
                append("}")
            }
            
            manifestFile.writeText(json)
            Log.i(TAG, "Installation manifest written to ${manifestFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write manifest: ${e.message}", e)
        }
    }

    private fun publishProgress(phase: String, current: Long, total: Long) {
        val percent = if (total > 0) ((current.toFloat() / total) * 100).toInt() else 0
        val progress = InstallProgress(phase, current, total, percent)
        
        stateManager.setInstallProgress(percent)
        progressCallback?.invoke(progress)
        
        Log.d(TAG, "Progress: $phase ${percent}% ($current/$total bytes)")
    }

    /**
     * Repair corrupted installation
     */
    fun repair(): Boolean {
        Log.i(TAG, "Starting repair operation")
        
        return try {
            // Check health
            if (!isHealthy()) {
                Log.i(TAG, "Installation not healthy, attempting repair...")
                
                // Attempt basic repairs:
                // 1. Check for broken symlinks
                // 2. Verify file permissions
                // 3. Re-extract critical files if missing
                
                // If repairs fail, recommend full reinstall
                stateManager.setInstallError("Repair incomplete. Please reinstall.")
                return false
            }
            
            Log.i(TAG, "Repair successful")
            stateManager.setRuntimeHealthy(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Repair failed: ${e.message}", e)
            false
        }
    }

    /**
     * Check installation health
     */
    private fun isHealthy(): Boolean {
        return debianDir.exists() && 
               File(debianDir, "bin/bash").exists() &&
               File(debianDir, "usr/bin/apt").exists()
    }

    /**
     * Cleanup temporary files
     */
    fun cleanup() {
        try {
            tempDir.deleteRecursively()
            Log.i(TAG, "Temporary files cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
        }
    }

    /**
     * Uninstall Debian environment (factory reset)
     */
    /**
     * Copy shell wrapper script into Debian bin directory
     */
    private fun copyShellWrapperToDebianBin(debianDir: File) {
        val binDir = File(debianDir, "bin")
        binDir.mkdirs()
        val username = stateManager.getConfig().username ?: "devpocket"
        val appDataDir = "/data/data/${context.packageName}"
        
        val wrapperFile = File(binDir, "devpocket-shell")
        val wrapperScript = """#!/system/bin/sh
# DevPocket shell wrapper - sets up Debian environment
export DEVPOCKET_DEBIAN_ROOT="${appDataDir}/files/linux/debian"
export DEVPOCKET_APP_CACHE="${appDataDir}/cache"
export DEVPOCKET_APP_FILES="${appDataDir}/files"
export DEVPOCKET_RUNTIME_BIN="${appDataDir}/files/runtime/bin"
export USER="$username"
export LOGNAME="$username"
export HOME="${'$'}{DEVPOCKET_DEBIAN_ROOT}/home/$username"
export TERM=xterm-256color
export COLORTERM=truecolor
export LC_ALL=C.UTF-8
export LANG=C.UTF-8
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export PROOT_TMP_DIR="${'$'}{DEVPOCKET_APP_CACHE}/proot-tmp"

PROOT_BIN="${'$'}{DEVPOCKET_RUNTIME_BIN}/proot"
FALLBACK_SHELL="/system/bin/sh"
run_fallback_shell() {
  export PATH="${'$'}{DEVPOCKET_RUNTIME_BIN}:${'$'}PATH"
  export LD_LIBRARY_PATH="${'$'}{DEVPOCKET_RUNTIME_BIN}/../lib:${'$'}LD_LIBRARY_PATH"
  export PS1='android@devpocket:\w\\$ '
  if [ "$1" = "-lc" ] && [ -n "$2" ]; then
    shift
    exec "${'$'}FALLBACK_SHELL" -c "$1"
  fi
  if [ "$1" = "-l" ] && [ "$2" = "-c" ] && [ -n "$3" ]; then
    shift 2
    exec "${'$'}FALLBACK_SHELL" -c "$1"
  fi
  if [ "$1" = "-c" ] && [ -n "$2" ]; then
    shift
    exec "${'$'}FALLBACK_SHELL" -c "$1"
  fi
  exec "${'$'}FALLBACK_SHELL" -i
}
if [ ! -x "${'$'}PROOT_BIN" ]; then
  echo "DevPocket warning: missing proot runtime at ${'$'}PROOT_BIN; using bundled shell." >&2
  run_fallback_shell "$@"
fi

mkdir -p "${'$'}PROOT_TMP_DIR" "${'$'}{DEVPOCKET_APP_CACHE}/android-tmp"

"${'$'}PROOT_BIN" -0 -r "${'$'}{DEVPOCKET_DEBIAN_ROOT}" \
  -w "/home/$username" \
  /bin/bash "$@"

status="${'$'}?"
echo "DevPocket warning: Debian shell failed with status ${'$'}status; falling back to bundled shell." >&2
run_fallback_shell "$@"
"""
        
        wrapperFile.writeText(wrapperScript)
        wrapperFile.setExecutable(true, false)
        Log.i(TAG, "Shell wrapper created at: ${wrapperFile.absolutePath}")
    }

    fun uninstall(): Boolean {
        return try {
            debianDir.deleteRecursively()
            manifestFile.delete()
            tempDir.deleteRecursively()
            stateManager.reset()
            Log.i(TAG, "Uninstall successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall failed: ${e.message}", e)
            false
        }
    }
}
