package com.example.summitgecko

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves the bundled probe test pages from loopback HTTP so GeckoView treats them
 * as ordinary web origins and the Summit Probe content script can be injected.
 * The server binds only to 127.0.0.1 and is stopped with the Activity.
 */
internal class LocalProbeTestServer(private val assets: AssetManager) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    val baseUrl: String
        get() = "http://127.0.0.1:${server?.localPort ?: error("Server not started")}"

    @Synchronized
    fun start(): String {
        if (running.get()) return baseUrl
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        running.set(true)
        worker = Thread({ serve(socket) }, "Summit-Probe-TestServer").apply {
            isDaemon = true
            start()
        }
        return baseUrl
    }

    private fun serve(serverSocket: ServerSocket) {
        while (running.get()) {
            try {
                val client = serverSocket.accept()
                client.soTimeout = 3000
                handle(client)
            } catch (_: IOException) {
                if (running.get()) continue else break
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
                )
                val requestLine = reader.readLine().orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
                val page = when (path) {
                    "/", "/cover.html", "/test" -> "gecko_test/cover.html"
                    "/default.html" -> "gecko_test/default.html"
                    else -> null
                }

                val body = if (page != null) {
                    try {
                        assets.open(page).use { it.readBytes() }
                    } catch (_: IOException) {
                        "Test page asset not found".toByteArray(StandardCharsets.UTF_8)
                    }
                } else {
                    "Not found".toByteArray(StandardCharsets.UTF_8)
                }
                val status = if (page != null) "200 OK" else "404 Not Found"
                val contentType = if (page != null) "text/html; charset=utf-8" else "text/plain; charset=utf-8"
                val headers = "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n"
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                if (!requestLine.startsWith("HEAD ")) output.write(body)
                output.flush()
            } catch (_: IOException) {
                // A navigation can be cancelled while Gecko is replacing a page.
            }
        }
    }

    @Synchronized
    override fun close() {
        running.set(false)
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        worker = null
    }
}
