package com.theia.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant

class RotatingFileLogger(context: Context, private val prefix: String) {

    companion object {
        private const val TAG = "TheiaFileLogger"
        private const val MAX_FILE_SIZE_BYTES = 512L * 1024L
        private const val MAX_LOG_FILES = 5
    }

    private val logsDir: File = TheiaRuntimePaths.logsRoot(context)
    private var currentFile: File? = null

    init {
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.w(TAG, "Failed to create logs dir: ${logsDir.absolutePath}")
        }
        rotateToNewFile()
    }

    @Synchronized
    fun log(line: String) {
        if (currentFile == null) {
            rotateToNewFile()
        }
        val file = currentFile ?: return

        try {
            if (file.length() > MAX_FILE_SIZE_BYTES) {
                rotateToNewFile()
            }
            FileWriter(currentFile, true).use { writer ->
                writer.write(Instant.now().toString())
                writer.write(" ")
                writer.write(line)
                writer.write("\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed writing log", e)
        }
    }

    private fun rotateToNewFile() {
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            return
        }
        currentFile = File(logsDir, "$prefix-${System.currentTimeMillis()}.log")
        trimOldFiles()
    }

    private fun trimOldFiles() {
        val candidates = logsDir.listFiles { _, name ->
            name.startsWith("$prefix-") && name.endsWith(".log")
        } ?: return

        if (candidates.size <= MAX_LOG_FILES) return

        val sorted = candidates.sortedBy { it.lastModified() }.toMutableList()
        while (sorted.size > MAX_LOG_FILES) {
            val toDelete = sorted.removeAt(0)
            if (!toDelete.delete()) {
                Log.w(TAG, "Failed deleting old log: ${toDelete.absolutePath}")
            }
        }
    }
}
