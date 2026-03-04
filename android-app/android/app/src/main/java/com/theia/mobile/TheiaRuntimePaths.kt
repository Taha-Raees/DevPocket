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
        File(runtimeRoot(context), "bin/node-wrapper")

    fun configDir(context: Context): File =
        File(context.filesDir, ".theia-android-lite")

    fun ovsxRouterConfig(context: Context): File =
        File(runtimeRoot(context), "config/ovsx-router-config.json")
}
