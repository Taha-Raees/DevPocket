/**
 * Phase 8: Theia Backend Integration
 * Manages backend lifecycle and Debian-aware configuration.
 *
 * Backend runs in the embedded Termux-like host runtime.
 * Terminal sessions always route through Debian via devpocket-shell.
 */

package com.theia.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL
import java.net.HttpURLConnection

object TheiaBackendConfig {
    private const val TAG = "TheiaBackendConfig"
    
    const val BACKEND_PORT = 3100
    const val BACKEND_HOST = "127.0.0.1"
    const val BACKEND_STARTUP_TIMEOUT_MS = 60_000L
    const val BACKEND_HEALTH_CHECK_INTERVAL_MS = 1000L
    
    fun getBackendEnvironmentVariables(context: Context): Map<String, String> {
        val env = mutableMapOf<String, String>()
        
        val stateManager = OnboardingStateManager(context)
        if (stateManager.isOnboardingComplete()) {
            val config = stateManager.getConfig()
            val debianRoot = getDebianRoot(context).absolutePath
            val wrapperPath = File(debianRoot, "bin/devpocket-shell").absolutePath
            
            env["DEVPOCKET_DEBIAN_ROOT"] = debianRoot
            env["DEVPOCKET_USER"] = config.username ?: "devpocket"
            env["DEVPOCKET_WORKSPACE"] = TheiaRuntimePaths.getIdeWorkspace(context).absolutePath
            env["THEIA_SHELL"] = wrapperPath
            
            env["DEVPOCKET_ROOTFS_VERSION"] = config.rootfsVersion ?: ""
            env["DEVPOCKET_BOOTSTRAP_VERSION"] = "1.0.0"
        }
        
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["WINCH_HANDLE_ASYNC"] = "1"
        
        return env
    }
    
    fun getDebianRoot(context: Context): File = 
        File(context.filesDir, "linux/debian")
    
    fun validateDebianSetup(context: Context): Boolean {
        val stateManager = OnboardingStateManager(context)
        
        if (!stateManager.isOnboardingComplete()) {
            Log.w(TAG, "Debian not installed - terminal will use host shell only")
            return true
        }
        
        val debianRoot = getDebianRoot(context)
        val userHome = File(debianRoot, "home/${stateManager.getConfig().username}")
        
        if (!userHome.exists()) {
            Log.e(TAG, "Debian user home not found: ${userHome.absolutePath}")
            return false
        }
        
        val wrapper = File(debianRoot, "bin/devpocket-shell")
        if (!wrapper.exists()) {
            Log.e(TAG, "Shell wrapper not found: ${wrapper.absolutePath}")
            return false
        }

        val prootBinary = File(context.filesDir, "runtime/bin/proot")
        if (!prootBinary.exists() || !prootBinary.canExecute()) {
            Log.e(TAG, "PRoot runtime missing or not executable: ${prootBinary.absolutePath}")
            return false
        }
        
        Log.i(TAG, "Debian setup validated successfully")
        return true
    }
    
    data class HealthCheckResult(
        var backendHealthy: Boolean = false,
        var debianHealthy: Boolean = true,
        var responseTimeMs: Long = 0,
        var errorMessage: String? = null
    )
    
    fun performHealthCheck(context: Context): HealthCheckResult {
        val result = HealthCheckResult()
        
        val startTime = System.currentTimeMillis()
        
        try {
            val url = URL("http://$BACKEND_HOST:$BACKEND_PORT/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.connect()
            val status = conn.responseCode
            conn.disconnect()
            
            result.backendHealthy = (status == 404 || status == 200 || status == 301)
        } catch (e: Exception) {
            result.backendHealthy = false
            result.errorMessage = "Backend unreachable: ${e.message}"
        }
        
        val stateManager = OnboardingStateManager(context)
        if (stateManager.isOnboardingComplete()) {
            val debianRoot = getDebianRoot(context)
            result.debianHealthy = debianRoot.exists() && 
                                   File(debianRoot, "home/${stateManager.getConfig().username}").exists()
        }
        
        result.responseTimeMs = System.currentTimeMillis() - startTime
        
        return result
    }
}
