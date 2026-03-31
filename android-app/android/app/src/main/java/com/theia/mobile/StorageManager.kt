/**
 * Phase 10: Storage Permissions & Import/Export
 * Manages project import/export and migration from external storage
 */

package com.theia.mobile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.StatFs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object StorageManager {
    private const val TAG = "StorageManager"
    
    // Supported project file types
    val SUPPORTED_FORMATS = arrayOf(".zip", ".tar", ".tar.gz")
    
    /**
     * Import project from external URI (user selects file via file picker)
     * Supports .zip format for cross-platform compatibility
     */
    data class ImportResult(
        var success: Boolean = false,
        var projectPath: String = "",
        var errorMessage: String = "",
        var bytesImported: Long = 0
    )
    
    fun importProject(context: Context, sourceUri: Uri, projectName: String): ImportResult {
        val result = ImportResult()
        val resolver = context.contentResolver
        
        return try {
            val destDir = File(TheiaRuntimePaths.getIdeWorkspace(context), projectName)
            
            if (destDir.exists()) {
                result.errorMessage = "Project already exists"
                return result
            }
            
            destDir.mkdirs()
            
            resolver.openInputStream(sourceUri)?.use { input ->
                when {
                    sourceUri.toString().endsWith(".zip") -> {
                        result.bytesImported = unzipProject(input, destDir)
                    }
                    sourceUri.toString().endsWith(".tar.gz") || sourceUri.toString().endsWith(".tar") -> {
                        // Would use tar library in production
                        result.errorMessage = "Tar import coming in Phase 10B"
                        return result
                    }
                    else -> {
                        result.errorMessage = "Unsupported file format"
                        return result
                    }
                }
            }
            
            result.success = true
            result.projectPath = destDir.absolutePath
            Log.i(TAG, "Project imported: $projectName (${result.bytesImported} bytes)")
            result
            
        } catch (e: IOException) {
            Log.e(TAG, "Import failed", e)
            result.errorMessage = e.message ?: "Unknown error"
            result
        }
    }
    
    /**
     * Export project to external storage (device's Downloads or Documents)
     */
    data class ExportResult(
        var success: Boolean = false,
        var exportUri: Uri? = null,
        var bytesExported: Long = 0,
        var errorMessage: String = ""
    )
    
    fun exportProject(context: Context, projectPath: String): ExportResult {
        val result = ExportResult()
        
        return try {
            val projectDir = File(projectPath)
            if (!projectDir.exists()) {
                result.errorMessage = "Project not found"
                return result
            }
            val cacheDir = context.cacheDir
            val zipFile = File(cacheDir, projectDir.name + ".zip")
            
            result.bytesExported = zipProject(projectDir, zipFile)
            result.success = true
            
            // In production: Use ContentProvider to share or copy to shared storage
            Log.i(TAG, "Project exported: ${zipFile.absolutePath} (${result.bytesExported} bytes)")
            result
            
        } catch (e: IOException) {
            Log.e(TAG, "Export failed", e)
            result.errorMessage = e.message ?: "Unknown error"
            result
        }
    }
    
    /**
     * Migrate from external storage to app-private workspace
     * Preserves old location, copies to new location
     */
    data class MigrationResult(
        var success: Boolean = false,
        var projectsMigrated: Int = 0,
        var migratedProjects: MutableList<String> = mutableListOf(),
        var errorMessage: String = ""
    )
    
    fun migrateFromExternalStorage(context: Context): MigrationResult {
        val result = MigrationResult()
        
        // Check for legacy external storage location
        val legacyDir = File("/storage/emulated/0/Documents/DevPocket")
        if (!legacyDir.exists()) {
            result.success = true
            result.errorMessage = "No legacy projects found"
            return result
        }
        
        return try {
            val destWorkspace = TheiaRuntimePaths.getIdeWorkspace(context)
            destWorkspace.mkdirs()
            
            val projects = legacyDir.listFiles { f -> f.isDirectory } ?: arrayOf()
            for (project in projects) {
                try {
                    val destProject = File(destWorkspace, project.name)
                    if (!destProject.exists()) {
                        copyDirectory(project, destProject)
                        result.migratedProjects.add(project.name)
                        result.projectsMigrated++
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to migrate ${project.name}", e)
                }
            }
            
            result.success = true
            Log.i(TAG, "Migration complete: ${result.projectsMigrated} projects")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            result.errorMessage = e.message ?: "Unknown error"
            result
        }
    }
    
    /**
     * Get storage statistics (useful for UI feedback)
     */
    data class StorageStats(
        var totalUsedBytes: Long = 0,
        var availableBytes: Long = 0,
        var workspaceBytes: Long = 0,
        var debianBytes: Long = 0
    )
    
    fun getStorageStats(context: Context): StorageStats {
        val stats = StorageStats()
        val workspace = TheiaRuntimePaths.getIdeWorkspace(context)
        val debian = TheiaRuntimePaths.getDebianRoot(context)
        
        stats.workspaceBytes = dirSize(workspace)
        stats.debianBytes = dirSize(debian)
        stats.totalUsedBytes = stats.workspaceBytes + stats.debianBytes
        
        // Available space on device
        val statFs = StatFs(context.filesDir.absolutePath)
        stats.availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        
        return stats
    }
    
    // Helper methods
    private fun unzipProject(input: InputStream, destDir: File): Long {
        var bytes = 0L
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                            bytes += len
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        return bytes
    }
    
    private fun zipProject(sourceDir: File, zipFile: File): Long {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zipDir(sourceDir, "", zos)
        }
        return zipFile.length()
    }
    
    private fun zipDir(dir: File, prefix: String, zos: ZipOutputStream) {
        dir.listFiles()?.forEach { file ->
            val name = prefix + (if (prefix.isEmpty()) "" else "/") + file.name
            
            if (file.isDirectory) {
                zipDir(file, name, zos)
            } else {
                zos.putNextEntry(ZipEntry(name))
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(4096)
                    var len: Int
                    while (fis.read(buffer).also { len = it } > 0) {
                        zos.write(buffer, 0, len)
                    }
                }
                zos.closeEntry()
            }
        }
    }
    
    private fun copyDirectory(source: File, dest: File) {
        if (source.isFile) {
            copyFile(source, dest)
            return
        }
        
        dest.mkdirs()
        source.listFiles()?.forEach { file ->
            copyDirectory(file, File(dest, file.name))
        }
    }
    
    private fun copyFile(source: File, dest: File) {
        dest.parentFile?.mkdirs()
        FileInputStream(source).use { fis ->
            FileOutputStream(dest).use { fos ->
                val buffer = ByteArray(4096)
                var len: Int
                while (fis.read(buffer).also { len = it } > 0) {
                    fos.write(buffer, 0, len)
                }
            }
        }
    }
    
    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        
        if (dir.isFile) {
            return dir.length()
        }
        
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) dirSize(file) else file.length()
        }
        return size
    }
}
