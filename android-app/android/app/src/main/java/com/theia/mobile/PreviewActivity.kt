package com.theia.mobile

import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        webView.webViewClient = LocalhostOnlyWebViewClient(this)
        webView.webChromeClient = WebChromeClient()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }

        val url = resolvePreviewUrl()
        Log.i(TAG, "Opening preview URL: $url")
        webView.loadUrl(url)
    }

    private fun resolvePreviewUrl(): String {
        val explicit = intent.getStringExtra("previewUrl")
        if (explicit != null && explicit.lowercase(Locale.ROOT).startsWith("http://127.0.0.1:")) {
            return explicit
        }

        val port = readDetectedPreviewPort()
        if (port > 0) {
            return "http://127.0.0.1:$port/"
        }

        return "http://127.0.0.1:3000/"
    }

    private fun readDetectedPreviewPort(): Int {
        val statusFile = File(filesDir, "preview-status.json")
        if (!statusFile.exists()) return -1
        return try {
            val json = statusFile.readText()
            val marker = json.indexOf("\"port\":")
            if (marker < 0) return -1
            var start = marker + 7
            while (start < json.length && !json[start].isDigit()) start++
            var end = start
            while (end < json.length && json[end].isDigit()) end++
            if (end > start) json.substring(start, end).toInt() else -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read preview status", e)
            -1
        }
    }
}
