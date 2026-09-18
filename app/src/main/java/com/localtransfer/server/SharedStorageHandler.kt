package com.localtransfer.server

import com.localtransfer.storage.StorageAccessException
import com.localtransfer.storage.StorageProvider

/**
 * Handles everything under /files/... against the user's shared-folder StorageProvider.
 * This is the "shared storage" side of the app, separate from the web-root static
 * handler, per the architecture's requirement to keep the two concepts distinct even
 * though both are ultimately StorageProvider-backed.
 */
class SharedStorageHandler(private val storage: StorageProvider) {

    fun handle(req: HttpRequest, res: HttpResponseWriter, relativePath: String) {
        when (req.method) {
            HttpMethod.GET, HttpMethod.HEAD -> handleDownloadOrList(req, res, relativePath)
            HttpMethod.PUT -> handleUpload(req, res, relativePath)
            HttpMethod.DELETE -> handleDelete(res, relativePath)
            else -> res.writeHeaders(405, linkedMapOf("Allow" to "GET, HEAD, PUT, DELETE", "Content-Length" to "0"))
        }
    }

    private fun handleDownloadOrList(req: HttpRequest, res: HttpResponseWriter, path: String) {
        val entry = storage.stat(path)
        if (entry == null) {
            res.writeBodyString(404, "Not found: $path")
            return
        }
        if (entry.isDirectory) {
            // Directory browsing over plain GET is handled by /api/list (JSON) for the
            // custom UI; a bare GET on a directory just confirms it exists.
            res.writeBodyString(200, "Directory: $path")
            return
        }
        RangeHandler.serve(req, res, entry.size, entry.lastModified, MimeTypes.forFileName(entry.name)) { start ->
            storage.openRead(path, start)
        }
    }

    private fun handleUpload(req: HttpRequest, res: HttpResponseWriter, path: String) {
        if (req.header("content-length") == null) {
            res.writeBodyString(411, "Content-Length required for uploads")
            return
        }
        try {
            storage.openWrite(path).use { out ->
                val buf = ByteArray(COPY_BUFFER_SIZE)
                var remaining = req.contentLength
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = req.body.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            res.writeBodyString(201, "Uploaded: $path")
        } catch (e: StorageAccessException) {
            res.writeBodyString(403, e.message ?: "Forbidden")
        } catch (e: Exception) {
            res.writeBodyString(500, "Upload failed: ${e.message}")
        }
    }

    private fun handleDelete(res: HttpResponseWriter, path: String) {
        val ok = try {
            storage.delete(path)
        } catch (e: StorageAccessException) {
            res.writeBodyString(403, e.message ?: "Forbidden"); return
        }
        if (ok) res.writeBodyString(204, "") else res.writeBodyString(404, "Not found: $path")
    }
}
