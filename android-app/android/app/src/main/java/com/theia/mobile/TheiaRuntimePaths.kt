package com.theia.mobile

import android.content.Context
import java.io.File

object TheiaRuntimePaths {

    private const val DEBIAN_RUNTIME_ROOT = "/opt/devpocket"
    private const val DEBIAN_CONFIG_ROOT = "/opt/devpocket-config"
    private const val DEBIAN_EXTENSIONS_ROOT = "/opt/devpocket-extensions"

    fun runtimeRoot(context: Context): File =
        File(context.filesDir, "runtime")

    fun termuxPrefix(context: Context): File =
        File(context.filesDir, "usr")

    fun termuxHome(context: Context): File =
        File(context.filesDir, "home")

    fun termuxTmp(context: Context): File =
        File(termuxPrefix(context), "tmp")

    fun termuxBin(context: Context): File =
        File(termuxPrefix(context), "bin")

    fun termuxLib(context: Context): File =
        File(termuxPrefix(context), "lib")

    fun termuxEnvWrapper(context: Context): File =
        File(termuxBin(context), "devpocket-termux-env")

    fun termuxCompatWrapper(context: Context): File =
        File(termuxBin(context), "devpocket-termux-compat")

    fun termuxShellWrapper(context: Context): File =
        File(termuxBin(context), "devpocket-shell")

    fun logsRoot(context: Context): File =
        File(context.filesDir, "logs")

    fun workspacesRoot(context: Context): File =
        File(context.filesDir, "workspaces")

    fun extensionsRoot(context: Context): File =
        File(context.filesDir, "theia/extensions")

    fun backendEntrypoint(context: Context): File =
        File(runtimeRoot(context), "theia-android-lite/lib/backend/main.js")

    fun nodeBinary(context: Context): File =
        File(termuxBin(context), "node")

    fun configDir(context: Context): File =
        File(context.filesDir, ".theia-android-lite")

    fun ovsxRouterConfig(context: Context): File =
        File(runtimeRoot(context), "config/ovsx-router-config.json")

    /**
     * Get Debian rootfs root directory
     */
    fun getDebianRoot(context: Context): File =
        File(termuxPrefix(context), "var/lib/proot-distro/installed-rootfs/debian")

    /**
     * Resolve the default Debian home path on the host filesystem.
     *
     * The backend uses this for HOME and terminal hand-off, but it is no longer passed as the
     * startup workspace argument to Theia. That keeps the IDE from auto-opening `/home/<user>/code`
     * on every launch while still giving Debian sessions a stable home directory.
     */
    fun getIdeWorkspace(context: Context): File {
        val onboardingManager = OnboardingStateManager(context)
        val config = onboardingManager.getConfig()

        val username = config.username?.takeIf { it.isNotBlank() } ?: "root"
        return if (username == "root") {
            File(getDebianRoot(context), "root")
        } else {
            File(getDebianRoot(context), "home/$username")
        }
    }
    
    /**
     * Get legacy external storage workspace (for migration only)
     * Users should explicitly opt-in to use external storage
     */
    fun getLegacyExternalWorkspace(): File =
        File("/storage/emulated/0/Documents/DevPocket")

    fun debianGuestRuntimeRoot(): String =
        DEBIAN_RUNTIME_ROOT

    fun debianGuestNodeBinary(): String =
        "$DEBIAN_RUNTIME_ROOT/bin/node"

    fun debianGuestBackendEntrypoint(): String =
        "$DEBIAN_RUNTIME_ROOT/theia-android-lite/lib/backend/main.js"

    fun debianGuestProjectRoot(): String =
        "$DEBIAN_RUNTIME_ROOT/theia-android-lite"

    fun debianGuestConfigDir(): String =
        DEBIAN_CONFIG_ROOT

    fun debianGuestExtensionsDir(): String =
        DEBIAN_EXTENSIONS_ROOT

    fun debianGuestRuntimeBin(): String =
        "$DEBIAN_RUNTIME_ROOT/bin"

    fun debianGuestRuntimeLib(): String =
        "$DEBIAN_RUNTIME_ROOT/lib"

    fun debianGuestCaCertPath(): String =
        "$DEBIAN_RUNTIME_ROOT/etc/ca-certificates/cacert.pem"

    fun debianGuestOvsxRouterConfig(): String =
        "$DEBIAN_RUNTIME_ROOT/config/ovsx-router-config.json"

    fun debianGuestHome(context: Context): String {
        val username = OnboardingStateManager(context).getConfig().username ?: "root"
        return if (username == "root") "/root" else "/home/$username"
    }

    fun debianGuestWorkspace(context: Context): String =
        debianGuestHome(context)
}
