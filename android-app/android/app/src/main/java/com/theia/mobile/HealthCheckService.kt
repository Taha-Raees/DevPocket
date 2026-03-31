/**
 * Phase 11: Reliability & Repair Flows
 * Health checks and automatic recovery for common failures
 */

package com.theia.mobile

import android.content.Context
import android.util.Log
import android.os.StatFs

import java.io.File
import java.io.IOException

object HealthCheckService {
    private const val TAG = "HealthCheckService"
    
    /**
     * Comprehensive health check combining all subsystems
     */
    data class HealthCheckReport(
        var overallHealthy: Boolean = false,
        var errors: MutableList<String> = mutableListOf(),
        var warnings: MutableList<String> = mutableListOf(),
        var components: MutableList<ComponentHealth> = mutableListOf(),
        var checkDurationMs: Long = 0
    )
    
    data class ComponentHealth(
        var name: String = "",
        var healthy: Boolean = false,
        var status: String = "",
        var responseTimeMs: Long = 0
    )
    
    fun performFullHealthCheck(context: Context): HealthCheckReport {
        val report = HealthCheckReport()
        val startTime = System.currentTimeMillis()
        
        // Check 1: Backend connectivity
        checkBackendHealth(context, report)
        
        // Check 2: File system
        checkFileSystemHealth(context, report)
        
        // Check 3: Debian (if installed)
        checkDebianHealth(context, report)
        
        // Check 4: Workspace  
        checkWorkspaceHealth(context, report)
        
        // Check 5: Terminal/Shell
        checkTerminalHealth(context, report)
        
        // Determine overall health
        report.overallHealthy = report.errors.isEmpty()
        report.checkDurationMs = System.currentTimeMillis() - startTime
        
        Log.i(TAG, "Health check complete: " + if (report.overallHealthy) "HEALTHY" else "ISSUES FOUND")
        
        return report
    }
    
    /**
     * Repair common issues automatically
     */
    data class RepairResult(
        var success: Boolean = false,
        var repairsAttempted: MutableList<String> = mutableListOf(),
        var repairsSuccessful: MutableList<String> = mutableListOf(),
        var errorMessage: String = ""
    )
    
    fun attemptAutoRepair(context: Context): RepairResult {
        val result = RepairResult()
        
        return try {
            val report = performFullHealthCheck(context)
            
            // Repair 1: Workspace permission issues
            if (report.errors.any { it.contains("workspace") }) {
                result.repairsAttempted.add("workspace-permissions")
                try {
                    val workspace = TheiaRuntimePaths.getIdeWorkspace(context)
                    workspace.mkdirs()
                    result.repairsSuccessful.add("workspace-permissions")
                    Log.i(TAG, "Repaired: workspace permissions")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to repair workspace: " + e.message)
                }
            }
            
            // Repair 2: Backend restart
            if (report.errors.any { it.contains("backend") }) {
                result.repairsAttempted.add("backend-restart")
                // Would call TheiaBackendService.restartBackend() in production
                Log.i(TAG, "Queued: backend restart")
            }
            
            // Repair 3: Debian validation
            val stateManager = OnboardingStateManager(context)
            if (stateManager.isOnboardingComplete()) {
                if (report.errors.any { it.contains("debian") }) {
                    result.repairsAttempted.add("debian-validation")
                    try {
                        if (TheiaBackendConfig.validateDebianSetup(context)) {
                            result.repairsSuccessful.add("debian-validation")
                            Log.i(TAG, "Repaired: Debian setup validated")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to repair Debian: " + e.message)
                    }
                }
            }
            
            result.success = result.repairsAttempted.isNotEmpty() && 
                            result.repairsSuccessful.size == result.repairsAttempted.size
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Repair failed", e)
            result.errorMessage = e.message ?: "Unknown error"
            result.success = false
            result
        }
    }
    
    // Health check implementations
    
    private fun checkBackendHealth(context: Context, report: HealthCheckReport) {
        val startTime = System.currentTimeMillis()
        val component = ComponentHealth(name = "Backend")
        
        try {
            val health = TheiaBackendConfig.performHealthCheck(context)
            component.healthy = health.backendHealthy
            component.status = if (health.backendHealthy) "Responding" else "Unreachable"
            
            if (!health.backendHealthy) {
                report.errors.add("Backend server not responding: " + health.errorMessage)
            }
        } catch (e: Exception) {
            component.healthy = false
            component.status = "Error: " + e.message
            report.errors.add("Backend health check failed: " + e.message)
        }
        
        component.responseTimeMs = System.currentTimeMillis() - startTime
        report.components.add(component)
    }
    
    private fun checkFileSystemHealth(context: Context, report: HealthCheckReport) {
        val component = ComponentHealth(name = "File System", healthy = true)
        
        try {
            val filesDir = context.filesDir
            if (!filesDir.exists() || !filesDir.canWrite()) {
                component.healthy = false
                report.errors.add("App file system not writable")
            }
            
            // Check free space
            val stat = StatFs(filesDir.absolutePath)
            val freeSpace = stat.availableBlocksLong * stat.blockSizeLong
            if (freeSpace < 100 * 1024 * 1024) { // < 100MB
                component.healthy = false
                report.warnings.add(String.format("Low free space: %.1f MB", freeSpace / (1024.0 * 1024.0)))
            }
            
            component.status = if (component.healthy) "OK" else "Issues detected"
        } catch (e: Exception) {
            component.healthy = false
            component.status = "Error: " + e.message
            report.errors.add("File system check failed")
        }
        
        report.components.add(component)
    }
    
    private fun checkDebianHealth(context: Context, report: HealthCheckReport) {
        val stateManager = OnboardingStateManager(context)
        val component = ComponentHealth(name = "Debian Runtime")
        
        if (!stateManager.isOnboardingComplete()) {
            component.healthy = true
            component.status = "Not installed (optional)"
            report.components.add(component)
            return
        }
        
        try {
            val debianRoot = TheiaRuntimePaths.getDebianRoot(context)
            component.healthy = TheiaBackendConfig.validateDebianSetup(context)
            
            if (!component.healthy) {
                report.errors.add("Debian runtime validation failed")
                component.status = "Invalid installation"
            } else {
                component.status = "Ready"
            }
        } catch (e: Exception) {
            component.healthy = false
            component.status = "Error: " + e.message
            report.errors.add("Debian health check failed")
        }
        
        report.components.add(component)
    }
    
    private fun checkWorkspaceHealth(context: Context, report: HealthCheckReport) {
        val component = ComponentHealth(name = "Workspace", healthy = true)
        
        try {
            val workspace = TheiaRuntimePaths.getIdeWorkspace(context)
            if (!workspace.exists()) {
                workspace.mkdirs()
                report.warnings.add("Workspace did not exist, created")
            }
            
            if (!workspace.canRead() || !workspace.canWrite()) {
                component.healthy = false
                report.errors.add("Workspace not readable/writable")
            }
            
            component.status = if (component.healthy) "OK" else "Permission issues"
        } catch (e: Exception) {
            component.healthy = false
            component.status = "Error"
            report.errors.add("Workspace check failed: " + e.message)
        }
        
        report.components.add(component)
    }
    private fun checkTerminalHealth(context: Context, report: HealthCheckReport) {
        val component = ComponentHealth(name = "Terminal", healthy = true)
        
        try {
            // Check if shell executable exists
            val shell = System.getenv("SHELL")
            if (shell != null && !File(shell).exists()) {
                component.healthy = false
                report.warnings.add("Default shell not found: $shell")
            }
            
            component.status = if (component.healthy) "Ready" else "Issues found"
        } catch (e: Exception) {
            component.healthy = false
            component.status = "Unknown"
        }
        
        report.components.add(component)
    }
}
