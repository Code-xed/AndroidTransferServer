package com.localtransfer.server

import java.io.InputStream
import java.io.OutputStream

private val STATUS_TEXT = mapOf(
    200 to "OK", 201 to "Created", 204 to "No Content", 206 to "Partial Content",
    301 to "Moved Permanently", 304 to "Not Modified",
    400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
    405 to "Method Not Allowed", 409 to "Conflict", 411 to "Length Required",
    416 to "Range Not Satisfiable", 500 to "Internal Server Error",
    501 to "Not Implemented", 503 to "Service Unavailable"
)

/** Buffer size used for all file<->socket copies. Chosen as a reasonable middle
 *  ground for LAN throughput without over-allocating on a mobile heap. Tune via
 *  ServerConfig if profiling on target devices shows a better value. */
const val COPY_BUFFER_SIZE = 64 * 1024

class HttpResponseWriter(private val out: OutputStream) {

    fun writeHeaders(status: Int, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ')
            .append(STATUS_TEXT[status] ?: "Unknown").append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun writeBodyString(status: Int, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "text/plain; charset=utf-8"
        headers["Content-Length"] = bytes.size.toString()
        headers.putAll(extraHeaders)
        writeHeaders(status, headers)
        out.write(bytes)
        out.flush()
    }

    /** Streams exactly [length] bytes from [input] to the socket using a fixed-size
     *  buffer -- never materializes the whole payload in memory regardless of file size. */
    fun streamBody(input: InputStream, length: Long) {
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        out.flush()
    }

    fun rawOut(): OutputStream = out
}
