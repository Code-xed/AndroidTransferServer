package com.localtransfer.server

import com.localtransfer.storage.StorageProvider
import org.json.JSONArray
import org.json.JSONObject

class ApiHandler(private val storage: StorageProvider, private val port: Int, private val ipProvider: () -> String) {

    fun handleList(req: HttpRequest, res: HttpResponseWriter) {
        val path = req.query["path"] ?: "/"
        val entries = try {
            storage.list(path)
        } catch (e: Exception) {
            res.writeBodyString(400, "Cannot list: ${e.message}")
            return
        }
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("isDirectory", e.isDirectory)
                put("size", e.size)
                put("lastModified", e.lastModified)
            })
        }
        writeJson(res, arr.toString())
    }

    fun handleInfo(res: HttpResponseWriter) {
        val obj = JSONObject().apply {
            put("ip", ipProvider())
            put("port", port)
            put("url", "http://${ipProvider()}:$port/")
        }
        writeJson(res, obj.toString())
    }

    private fun writeJson(res: HttpResponseWriter, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        res.writeHeaders(200, linkedMapOf("Content-Type" to "application/json; charset=utf-8", "Content-Length" to bytes.size.toString()))
        res.rawOut().write(bytes)
        res.rawOut().flush()
    }
}
