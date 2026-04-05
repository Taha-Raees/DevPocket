/**
 * Phase 8: Theia Backend Integration
 * Manages backend lifecycle and Debian-aware configuration.
 *
 * Preferred runtime: launch the backend inside Debian via `devpocket-shell`.
 * Host runtime launch remains as the fallback path when Debian is unavailable.
 */

package com.theia.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL
import java.net.HttpURLConnection

object TheiaBackendConfig {
    private const val TAG = "TheiaBackendConfig"
    
    // Backend Configuration Constants
    const val BACKEND_PORT = 3100
    const val BACKEND_HOST = "127.0.0.1"
    const val BACKEND_STARTUP_TIMEOUT_MS = 30000L
    const val BACKEND_HEALTH_CHECK_INTERVAL_MS = 1000L
    
    // Host-mode fallback configuration.
    // When Debian launch is unavailable the backend still runs in the host runtime,
    // but terminals are routed through the Debian wrapper.
    
    fun getBackendEnvironmentVariables(context: Context): Map<String, String> {
        val env = mutableMapOf<String, String>()
        
        // Detect Debian runtime (if installed)
        val stateManager = OnboardingStateManager(context)
        if (stateManager.isOnboardingComplete()) {
            val config = stateManager.getConfig()
            val debianRoot = getDebianRoot(context).absolutePath
            val wrapperPath = File(debianRoot, "bin/devpocket-shell").absolutePath
            
            // Backend should know about Debian but doesn't run inside it
            // This allows it to validate Debian setup, provide UI feedback, etc.
            env["DEVPOCKET_DEBIAN_ROOT"] = debianRoot
            env["DEVPOCKET_USER"] = config.username ?: "devpocket"
            env["DEVPOCKET_WORKSPACE"] = TheiaRuntimePaths.getIdeWorkspace(context).absolutePath
            env["THEIA_SHELL"] = wrapperPath
            
            // Version tracking for upgrade detection
            env["DEVPOCKET_ROOTFS_VERSION"] = config.rootfsVersion ?: ""
            env["DEVPOCKET_BOOTSTRAP_VERSION"] = "1.0.0"
        }
        
        // Terminal server configuration (applies to all terminals)
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        
        // Disable terminal resize delay on Android
        env["WINCH_HANDLE_ASYNC"] = "1"
        
        return env
    }
    
    /**
     * Get Debian rootfs root directory
     */
    fun getDebianRoot(context: Context): File = 
        File(context.filesDir, "linux/debian")
    
    /**
     * Validate backend readiness
     * Returns immediately if Debian not installed (legacy mode)
     * Returns true if Debian routes properly
     */
    fun validateDebianSetup(context: Context): Boolean {
        val stateManager = OnboardingStateManager(context)
        
        if (!stateManager.isOnboardingComplete()) {
            Log.w(TAG, "Debian not installed - backend will fall back to host runtime")
            return true
        }
        
        val debianRoot = getDebianRoot(context)
        val userHome = File(debianRoot, "home/${stateManager.getConfig().username}")
        
        if (!userHome.exists()) {
            Log.e(TAG, "Debian user home not found: ${userHome.absolutePath}")
            return false
        }
        
        // Check for wrapper script
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
    
    /**
     * Health check for backend + Debian integration
     */
    data class HealthCheckResult(
        var backendHealthy: Boolean = false,
        var debianHealthy: Boolean = true,
        var responseTimeMs: Long = 0,
        var errorMessage: String? = null
    )
    
    fun performHealthCheck(context: Context): HealthCheckResult {
        val result = HealthCheckResult()
        
        val startTime = System.currentTimeMillis()
        
        // Check backend connectivity
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
        
        // Check Debian if installed
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
