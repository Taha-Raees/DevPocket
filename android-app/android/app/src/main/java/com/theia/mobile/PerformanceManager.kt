/**
 * Phase 13: Performance & Footprint
 * Disk usage UI, caching, and cleanup strategies
 */

package com.theia.mobile

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import android.os.StatFs

import java.io.File

object PerformanceManager {
    private const val TAG = "PerformanceManager"
    
    /**
     * Disk usage analysis by component
     */
    data class DiskUsageBreakdown(
        var totalUsedBytes: Long = 0,
        var debianDebianBytes: Long = 0,
        var workspaceBytes: Long = 0,
        var cacheBytes: Long = 0,
        var appBytes: Long = 0,
        var components: MutableList<ComponentUsage> = mutableListOf()
    )
    
    data class ComponentUsage(
        var name: String = "",
        var sizeBytes: Long = 0,
        var percentageOfTotal: Double = 0.0,
        var isRemovable: Boolean = false
    )
    
    fun calculateDiskUsage(context: Context): DiskUsageBreakdown {
        val breakdown = DiskUsageBreakdown()
        
        return try {
            val filesDir = context.filesDir
            val cacheDir = context.cacheDir
            
            // Calculate component sizes
            val debianRoot = TheiaRuntimePaths.getDebianRoot(context)
            val workspace = TheiaRuntimePaths.getIdeWorkspace(context)
            
            breakdown.debianDebianBytes = calculateDirSize(debianRoot)
            breakdown.workspaceBytes = calculateDirSize(workspace)
            breakdown.cacheBytes = calculateDirSize(cacheDir)
            breakdown.appBytes = calculateDirSize(filesDir) - breakdown.debianDebianBytes
            breakdown.totalUsedBytes = breakdown.debianDebianBytes + breakdown.workspaceBytes + 
                                       breakdown.cacheBytes + breakdown.appBytes
            
            // Create breakdown components
            if (breakdown.debianDebianBytes > 0) {
                addComponent(breakdown, "Debian Runtime", breakdown.debianDebianBytes, false)
            }
            addComponent(breakdown, "Workspace", breakdown.workspaceBytes, true)
            addComponent(breakdown, "Cache", breakdown.cacheBytes, true)
            addComponent(breakdown, "App Data", breakdown.appBytes, false)
            
            // Calculate percentages
            breakdown.components.forEach { comp ->
                comp.percentageOfTotal = (comp.sizeBytes / breakdown.totalUsedBytes.toDouble()) * 100
            }
            
            Log.i(TAG, String.format("Disk usage: %.1f GB total", 
                breakdown.totalUsedBytes / (1024.0 * 1024.0 * 1024.0)))
            breakdown
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate disk usage", e)
            breakdown
        }
    }
    
    /**
     * Memory usage and optimization
     */
    data class MemoryStats(
        var usedMemoryBytes: Long = 0,
        var maxMemoryBytes: Long = 0,
        var freeMemoryBytes: Long = 0,
        var usagePercent: Double = 0.0,
        var isLowMemory: Boolean = false
    )
    
    fun getMemoryStats(context: Context): MemoryStats {
        val stats = MemoryStats()
        val runtime = Runtime.getRuntime()
        
        stats.maxMemoryBytes = runtime.maxMemory()
        stats.usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory()
        stats.freeMemoryBytes = runtime.freeMemory()
        stats.usagePercent = (stats.usedMemoryBytes / stats.maxMemoryBytes.toDouble()) * 100
        
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        stats.isLowMemory = memoryInfo.lowMemory
        
        Log.i(TAG, String.format("Memory: %.1f MB / %.1f MB (%.1f%%)",
            stats.usedMemoryBytes / (1024.0 * 1024.0),
            stats.maxMemoryBytes / (1024.0 * 1024.0),
            stats.usagePercent))
        
        return stats
    }
    
    /**
     * Cleanup operations to free disk space
     */
    data class CleanupResult(
        var success: Boolean = false,
        var operationsPerformed: MutableList<String> = mutableListOf(),
        var bytesFreed: Long = 0,
        var errorMessage: String = ""
    )
    
    fun cleanupUnusedFiles(context: Context): CleanupResult {
        val result = CleanupResult()
        
        return try {
            // Clean 1: Clear old cache files
            val cacheDir = context.cacheDir
            val cacheFreed = cleanOldFiles(cacheDir, 7 * 24 * 60 * 60 * 1000) // 7 days
            if (cacheFreed > 0) {
                result.operationsPerformed.add("Cleared old cache")
                result.bytesFreed += cacheFreed
            }
            
            // Clean 2: Remove workspace backup files (.bak, .tmp)
            val workspace = TheiaRuntimePaths.getIdeWorkspace(context)
            val backupFreed = cleanBackupFiles(workspace)
            if (backupFreed > 0) {
                result.operationsPerformed.add("Removed workspace backups")
                result.bytesFreed += backupFreed
            }
            
            // Clean 3: Clear Docker/container temporary files (if applicable)
            val tempDir = File(context.filesDir, "tmp")
            val tempFreed = cleanDirectory(tempDir)
            if (tempFreed > 0) {
                result.operationsPerformed.add("Cleared temporary files")
                result.bytesFreed += tempFreed
            }
            
            result.success = true
            Log.i(TAG, String.format("Cleanup complete: %.1f MB freed",
                result.bytesFreed / (1024.0 * 1024.0)))
            result
            
        } catch (e: Exception) {
            result.errorMessage = e.message ?: "Unknown error"
            Log.e(TAG, "Cleanup failed", e)
            result
        }
    }
    
    /**
     * Enable aggressive caching for offline access
     */
    data class CachingConfiguration(
        var enableNodeModulesCache: Boolean = false,
        var enablePipCache: Boolean = false,
        var enableAptCache: Boolean = false,
        var maxCacheSizeBytes: Long = 0
    )
    
    fun getOptimalCachingConfig(context: Context): CachingConfiguration {
        val config = CachingConfiguration()
        
        val memory = getMemoryStats(context)
        val disk = calculateDiskUsage(context)
        
        // Enable caching based on available resources
        config.enableNodeModulesCache = memory.usagePercent < 80 && disk.totalUsedBytes < 5L * 1024 * 1024 * 1024
        config.enablePipCache = memory.usagePercent < 80
        config.enableAptCache = disk.totalUsedBytes < 6L * 1024 * 1024 * 1024
        
        // Set cache size to 10% of available space
        val freeSpace = getFreeSpace(context)
        config.maxCacheSizeBytes = freeSpace / 10
        
        return config
    }
    
    // Utility methods
    
    private fun addComponent(breakdown: DiskUsageBreakdown, name: String, size: Long, removable: Boolean) {
        val comp = ComponentUsage(name = name, sizeBytes = size, isRemovable = removable)
        breakdown.components.add(comp)
    }
    
    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }
    
    private fun cleanOldFiles(dir: File, ageMs: Long): Long {
        if (!dir.exists()) return 0
        
        var freed = 0L
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > ageMs) {
                freed += file.length()
                deleteRecursive(file)
            }
        }
        return freed
    }
    
    private fun cleanBackupFiles(dir: File): Long {
        if (!dir.exists()) return 0
        
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bak") || file.name.endsWith(".tmp")) {
                freed += calculateDirSize(file)
                deleteRecursive(file)
            }
        }
        return freed
    }
    
    private fun cleanDirectory(dir: File): Long {
        if (!dir.exists()) return 0
        
        val freed = calculateDirSize(dir)
        deleteRecursive(dir)
        return freed
    }
    
    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        file.delete()
    }
    
    private fun getFreeSpace(context: Context): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}
