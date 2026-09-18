package com.localtransfer.server

import java.io.EOFException
import java.io.InputStream
import java.net.URLDecoder

data class HttpRequest(
    val method: HttpMethod,
    val rawPath: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>, // lower-cased keys
    val httpVersion: String,
    val body: InputStream
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val contentLength: Long
        get() = header("content-length")?.toLongOrNull() ?: 0L

    val keepAlive: Boolean
        get() {
            val conn = header("connection")?.lowercase()
            return when {
                conn == "close" -> false
                httpVersion == "HTTP/1.1" -> conn != "close"
                else -> conn == "keep-alive"
            }
        }
}

/**
 * Minimal, defensive HTTP/1.1 request-line + header parser operating directly on the
 * socket's InputStream. Reads byte-by-byte for the header section only (small, bounded
 * cost) so the body stream is left untouched for zero-copy-ish streaming reads.
 */
object HttpRequestParser {

    private const val MAX_HEADER_LINE = 8192
    private const val MAX_HEADERS = 100

    /** Returns null on clean EOF (peer closed the socket between requests). */
    fun parse(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return parse(input) // tolerate leading blank lines

        val parts = requestLine.trim().split(" ")
        if (parts.size != 3) throw MalformedRequestException("Bad request line: $requestLine")

        val method = HttpMethod.fromString(parts[0])
        val rawTarget = parts[1]
        val version = parts[2]
        if (version != "HTTP/1.1" && version != "HTTP/1.0") {
            throw MalformedRequestException("Unsupported HTTP version: $version")
        }

        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: throw EOFException("Connection closed while reading headers")
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw MalformedRequestException("Too many headers")
            val idx = line.indexOf(':')
            if (idx <= 0) throw MalformedRequestException("Bad header line: $line")
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }

        val (pathPart, queryPart) = splitTarget(rawTarget)
        val decodedPath = decodePath(pathPart)
        val query = parseQuery(queryPart)

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        val bodyStream: InputStream = if (contentLength > 0) {
            BoundedInputStream(input, contentLength)
        } else {
            BoundedInputStream(input, 0)
        }

        return HttpRequest(method, rawTarget, decodedPath, query, headers, version, bodyStream)
    }

    private fun splitTarget(target: String): Pair<String, String?> {
        val q = target.indexOf('?')
        return if (q >= 0) target.substring(0, q) to target.substring(q + 1) else target to null
    }

    private fun decodePath(path: String): String {
        val decoded = try {
            URLDecoder.decode(path, "UTF-8")
        } catch (e: Exception) {
            throw MalformedRequestException("Bad URL encoding in path")
        }
        return decoded
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return map
    }

    /** Reads a single CRLF- or LF-terminated line, bounded to prevent memory abuse
     *  from a malformed/malicious client. Returns null on immediate EOF. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArray(MAX_HEADER_LINE)
        var len = 0
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) String(buf, 0, len, Charsets.ISO_8859_1) else null
            }
            sawAny = true
            if (b == '\n'.code) {
                if (len > 0 && buf[len - 1] == '\r'.code.toByte()) len--
                return String(buf, 0, len, Charsets.ISO_8859_1)
            }
            if (len >= MAX_HEADER_LINE) throw MalformedRequestException("Header line too long")
            buf[len++] = b.toByte()
        }
    }
}

class MalformedRequestException(message: String) : Exception(message)
