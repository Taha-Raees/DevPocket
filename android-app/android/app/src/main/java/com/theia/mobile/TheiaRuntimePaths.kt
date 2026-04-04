package com.theia.mobile

import android.content.Context
import java.io.File

object TheiaRuntimePaths {

    fun runtimeRoot(context: Context): File =
        File(context.filesDir, "runtime")

    fun logsRoot(context: Context): File =
        File(context.filesDir, "logs")

    fun workspacesRoot(context: Context): File =
        File(context.filesDir, "workspaces")

    fun extensionsRoot(context: Context): File =
        File(context.filesDir, "theia/extensions")

    fun backendEntrypoint(context: Context): File =
        File(runtimeRoot(context), "theia-android-lite/lib/backend/main.js")

    fun nodeBinary(context: Context): File =
        File(runtimeRoot(context), "bin/node")

    fun configDir(context: Context): File =
        File(context.filesDir, ".theia-android-lite")

    fun ovsxRouterConfig(context: Context): File =
        File(runtimeRoot(context), "config/ovsx-router-config.json")

    /**
     * Get Debian rootfs root directory
     */
    fun getDebianRoot(context: Context): File =
        File(context.filesDir, "linux/debian")

    /**
     * Resolve IDE workspace location with priority:
     * 1. App-private Debian home workspace (primary): $filesDir/linux/debian/home/<username>/code/
     * 2. Internal fallback: $filesDir/workspaces/primary/
     * 3. Legacy external (only if explicitly migrated): /storage/emulated/0/Documents/DevPocket/
     *
     * This ensures new users default to app-private, with fallback to internal storage,
     * avoiding external shared storage as default.
     */
    fun getIdeWorkspace(context: Context): File {
        val onboardingManager = OnboardingStateManager(context)
        val config = onboardingManager.getConfig()
        
        // Primary: Debian home workspace if onboarding complete with username
        if (config.username != null && config.username!!.isNotEmpty()) {
            val debianHome = File(context.filesDir, "linux/debian/home/${config.username}/code")
            // Return this path even if not yet created (will be created during install)
            return debianHome
        }
        
        // Fallback: Internal app-private workspace
        return File(context.filesDir, "workspaces/primary")
    }
    
    /**
     * Get legacy external storage workspace (for migration only)
     * Users should explicitly opt-in to use external storage
     */
    fun getLegacyExternalWorkspace(): File =
        File("/storage/emulated/0/Documents/DevPocket")
}
