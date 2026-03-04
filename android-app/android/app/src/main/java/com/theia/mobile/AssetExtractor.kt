package com.theia.mobile

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AssetExtractor {

    private const val TAG = "TheiaAssetExtractor"
    private const val ASSET_ROOT = "runtime"
    private const val MARKER_FILE = ".runtime-extracted-v3"

    private val EXECUTABLE_BASENAMES = setOf(
        "node", "node-wrapper", "npm", "npx", "rg", "git", "ssh", "busybox"
    )

    @Throws(IOException::class)
    fun ensureExtracted(context: Context) {
        val runtimeRoot = TheiaRuntimePaths.runtimeRoot(context)
        val marker = File(runtimeRoot, MARKER_FILE)
        if (marker.exists()) {
            ensureDirectories(context)
            return
        }

        deleteRecursively(runtimeRoot)
        if (!runtimeRoot.mkdirs() && !runtimeRoot.isDirectory) {
            throw IOException("Failed to create runtime root: $runtimeRoot")
        }

        extractAssetDirectory(context.assets, ASSET_ROOT, runtimeRoot)
        markExecutables(runtimeRoot)

        marker.writeText("ok\n")
        ensureDirectories(context)
        Log.i(TAG, "Runtime extracted to ${runtimeRoot.absolutePath}")
    }

    private fun ensureDirectories(context: Context) {
        mkdirs(TheiaRuntimePaths.logsRoot(context))
        mkdirs(TheiaRuntimePaths.workspacesRoot(context))
        mkdirs(TheiaRuntimePaths.extensionsRoot(context))
        mkdirs(TheiaRuntimePaths.configDir(context))
    }

    private fun mkdirs(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create directory: ${dir.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun extractAssetDirectory(assets: AssetManager, assetDir: String, outDir: File) {
        val entries = assets.list(assetDir)
        if (entries.isNullOrEmpty()) {
            copyAssetFile(assets, assetDir, outDir)
            return
        }

        for (entry in entries) {
            val childAssetPath = "$assetDir/$entry"
            val childOut = File(outDir, entry)
            val childEntries = assets.list(childAssetPath)
            if (!childEntries.isNullOrEmpty()) {
                if (!childOut.exists() && !childOut.mkdirs()) {
                    throw IOException("Failed to create directory $childOut")
                }
                extractAssetDirectory(assets, childAssetPath, childOut)
            } else {
                copyAssetFile(assets, childAssetPath, childOut)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyAssetFile(assets: AssetManager, assetPath: String, outputFile: File) {
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create parent directory for $outputFile")
            }
        }

        assets.open(assetPath).use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                }
                output.flush()
            }
        }
    }

    private fun markExecutables(root: File) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                    continue
                }
                if (shouldBeExecutable(child)) {
                    // Try Java API first
                    child.setExecutable(true, false)
                    child.setReadable(true, false)
                    // Fallback: use chmod via Runtime.exec (more reliable on Android)
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("chmod", "755", child.absolutePath))
                        proc.waitFor()
                    } catch (e: Exception) {
                        Log.w(TAG, "chmod fallback failed for ${child.absolutePath}: ${e.message}")
                    }
                    Log.i(TAG, "Marked executable: ${child.absolutePath} canExec=${child.canExecute()}")
                }
            }
        }
    }

    private fun shouldBeExecutable(file: File): Boolean {
        val name = file.name
        if (name in EXECUTABLE_BASENAMES) return true
        if (name.endsWith(".so") || name.endsWith(".node")) return true
        return file.parentFile?.name == "bin"
    }

    @Throws(IOException::class)
    private fun deleteRecursively(root: File) {
        if (!root.exists()) return
        val children = root.listFiles()
        if (children != null) {
            for (child in children) {
                deleteRecursively(child)
            }
        }
        if (!root.delete()) {
            throw IOException("Failed to delete ${root.absolutePath}")
        }
    }
}
