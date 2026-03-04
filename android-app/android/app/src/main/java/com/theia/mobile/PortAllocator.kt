package com.theia.mobile

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object PortAllocator {

    @Throws(IOException::class)
    fun findAvailablePort(startInclusive: Int, endExclusive: Int): Int {
        for (candidate in startInclusive until endExclusive) {
            if (isPortFree(candidate)) {
                return candidate
            }
        }
        throw IOException("No free port in range $startInclusive-${endExclusive - 1}")
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    fun waitForHttpReady(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 500)
                    return true
                }
            } catch (_: IOException) {
                try {
                    Thread.sleep(250L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }
}
