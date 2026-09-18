package com.localtransfer.server

import com.localtransfer.storage.StorageProvider

class RequestRouter(
    webRoot: StorageProvider,
    sharedStorage: StorageProvider,
    port: Int,
    ipProvider: () -> String
) {
    private val staticHandler = StaticFileHandler(webRoot)
    private val sharedHandler = SharedStorageHandler(sharedStorage)
    private val apiHandler = ApiHandler(sharedStorage, port, ipProvider)

    /** Every request from the connection loop passes through here inside a try/catch
     *  so one malformed/malicious request can never take down the server or affect
     *  other connections. */
    fun handle(req: HttpRequest, res: HttpResponseWriter) {
        try {
            when {
                req.path.startsWith("/files/") -> {
                    val rel = req.path.removePrefix("/files")
                    sharedHandler.handle(req, res, rel)
                }
                req.path == "/api/list" && req.method == HttpMethod.GET -> apiHandler.handleList(req, res)
                req.path == "/api/info" && req.method == HttpMethod.GET -> apiHandler.handleInfo(res)
                req.method == HttpMethod.GET || req.method == HttpMethod.HEAD -> staticHandler.handle(req, res)
                else -> res.writeHeaders(405, linkedMapOf("Content-Length" to "0"))
            }
        } catch (e: com.localtransfer.storage.StorageAccessException) {
            safeWrite(res, 403, e.message ?: "Forbidden")
        } catch (e: MalformedRequestException) {
            safeWrite(res, 400, e.message ?: "Bad request")
        } catch (e: Exception) {
            safeWrite(res, 500, "Internal error: ${e.message}")
        }
    }

    private fun safeWrite(res: HttpResponseWriter, status: Int, message: String) {
        try {
            res.writeBodyString(status, message)
        } catch (_: Exception) {
            // Socket already broken -- nothing more we can do; connection loop will close it.
        }
    }
}
