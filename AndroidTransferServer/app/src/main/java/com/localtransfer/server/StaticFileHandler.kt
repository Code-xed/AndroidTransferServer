package com.localtransfer.server

import com.localtransfer.storage.StorageNotFoundException
import com.localtransfer.storage.StorageProvider
import java.io.InputStream

/**
 * Serves GET/HEAD from a StorageProvider as plain static files (the web root use
 * case). Shares the same RangeHandler logic used for shared-storage downloads so
 * behavior (Range parsing, 206/416, headers) is identical everywhere in the app.
 */
class StaticFileHandler(private val storage: StorageProvider, private val indexFile: String = "index.html") {

    fun handle(req: HttpRequest, res: HttpResponseWriter) {
        var path = req.path
        if (path == "/" || path.isEmpty()) path = "/$indexFile"

        val entry = storage.stat(path)
        if (entry == null || entry.isDirectory) {
            res.writeBodyString(404, "Not found: ${req.path}")
            return
        }

        RangeHandler.serve(req, res, entry.size, entry.lastModified, MimeTypes.forFileName(entry.name)) { start ->
            storage.openRead(path, start)
        }
    }
}

/**
 * Shared Range/If-Modified handling used by both the web-root static handler and the
 * shared-storage file handler, so download semantics are consistent across the app.
 */
object RangeHandler {
    fun serve(
        req: HttpRequest,
        res: HttpResponseWriter,
        totalSize: Long,
        lastModified: Long,
        mimeType: String,
        openFrom: (start: Long) -> InputStream
    ) {
        val rangeHeader = req.header("range")
        val headMode = req.method == HttpMethod.HEAD

        if (rangeHeader == null) {
            val headers = linkedMapOf(
                "Content-Type" to mimeType,
                "Content-Length" to totalSize.toString(),
                "Accept-Ranges" to "bytes",
                "Last-Modified" to httpDate(lastModified)
            )
            res.writeHeaders(200, headers)
            if (!headMode) {
                val stream = openFrom(0)
                stream.use { res.streamBody(it, totalSize) }
            }
            return
        }

        val (start, end) = parseRange(rangeHeader, totalSize) ?: run {
            res.writeHeaders(416, linkedMapOf("Content-Range" to "bytes */$totalSize", "Content-Length" to "0"))
            return
        }
        val length = end - start + 1
        val headers = linkedMapOf(
            "Content-Type" to mimeType,
            "Content-Length" to length.toString(),
            "Content-Range" to "bytes $start-$end/$totalSize",
            "Accept-Ranges" to "bytes",
            "Last-Modified" to httpDate(lastModified)
        )
        res.writeHeaders(206, headers)
        if (!headMode) {
            val stream = openFrom(start)
            stream.use { res.streamBody(it, length) }
        }
    }

    /** Parses a single "bytes=start-end" range (the common case for browsers and
     *  resumable-download clients). Multi-range requests are not supported; we
     *  answer with a single-range response, which every mainstream client accepts. */
    private fun parseRange(header: String, totalSize: Long): Pair<Long, Long>? {
        if (!header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").substringBefore(',') // take first range only
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash)
        val endStr = spec.substring(dash + 1)
        val start: Long
        val end: Long
        if (startStr.isEmpty()) {
            // suffix range: "-500" = last 500 bytes
            val suffixLen = endStr.toLongOrNull() ?: return null
            if (suffixLen <= 0) return null
            start = maxOf(0, totalSize - suffixLen)
            end = totalSize - 1
        } else {
            start = startStr.toLongOrNull() ?: return null
            end = if (endStr.isEmpty()) totalSize - 1 else (endStr.toLongOrNull() ?: return null)
        }
        if (start < 0 || end < start || start >= totalSize) return null
        return start to minOf(end, totalSize - 1)
    }

    private fun httpDate(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return fmt.format(java.util.Date(epochMillis))
    }
}
