package com.localtransfer.server

object MimeTypes {
    private val map = mapOf(
        "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8", "js" to "application/javascript; charset=utf-8",
        "mjs" to "application/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8", "txt" to "text/plain; charset=utf-8",
        "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "ico" to "image/x-icon",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4",
        "pdf" to "application/pdf", "zip" to "application/zip",
        "xml" to "application/xml; charset=utf-8"
    )

    fun forFileName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return map[ext] ?: "application/octet-stream"
    }
}
