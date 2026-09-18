package com.localtransfer.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 server on raw sockets. One coroutine per connection (IO dispatcher,
 * backed by a bounded thread pool at the OS/runtime level) rather than a
 * thread-per-connection model or an NIO selector loop -- for a LAN file-transfer
 * server with realistically dozens (not thousands) of concurrent clients, this keeps
 * the code straightforward while coroutines make cancellation/cleanup manageable.
 * A selector-based reactor would out-scale this at very high connection counts, which
 * is not this app's use case.
 */
class HttpServer(
    private val port: Int,
    private val router: RequestRouter,
    private val maxConcurrentConnections: Int = 32
) {
    private var serverSocket: ServerSocket? = null
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private val activeConnections = AtomicInteger(0)

    @Volatile var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        isRunning = true

        scope.launch {
            while (isRunning) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break // socket closed by stop()
                }
                if (activeConnections.get() >= maxConcurrentConnections) {
                    // Defensive limit: reject politely instead of accepting unbounded
                    // connections that would degrade every other transfer.
                    rejectTooManyConnections(client)
                    continue
                }
                activeConnections.incrementAndGet()
                launch {
                    try {
                        handleConnection(client)
                    } finally {
                        activeConnections.decrementAndGet()
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        supervisor.cancel()
    }

    private fun rejectTooManyConnections(client: Socket) {
        try {
            client.getOutputStream().write(
                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            )
            client.close()
        } catch (_: Exception) {}
    }

    private fun handleConnection(client: Socket) {
        client.use { sock ->
            sock.tcpNoDelay = true // small control messages (headers) shouldn't wait on Nagle's algorithm
            val input = BufferedInputStream(sock.getInputStream(), COPY_BUFFER_SIZE)
            val output = BufferedOutputStream(sock.getOutputStream(), COPY_BUFFER_SIZE)
            val responseWriter = HttpResponseWriter(output)

            try {
                while (true) {
                    val request = try {
                        HttpRequestParser.parse(input)
                    } catch (e: MalformedRequestException) {
                        responseWriter.writeBodyString(400, "Bad request: ${e.message}", mapOf("Connection" to "close"))
                        break
                    } ?: break // clean EOF between requests

                    router.handle(request, responseWriter)

                    // Ensure any unread upload bytes are drained so the next request
                    // on this keep-alive connection starts at the correct offset.
                    (request.body as? BoundedInputStream)?.drainRemaining()

                    if (!request.keepAlive) break
                }
            } catch (e: Exception) {
                // Any per-connection failure (reset, broken pipe, etc.) just ends this
                // connection -- it must never propagate and affect other clients.
            }
        }
    }
}
