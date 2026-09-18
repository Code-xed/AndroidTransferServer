package com.localtransfer.server

/**
 * Supported HTTP/WebDAV methods. PROPFIND/MKCOL/COPY/MOVE are declared now so the
 * router and storage layer already have stable call sites; their handlers are
 * added in the WebDAV pass (not yet wired for this core-server milestone).
 */
enum class HttpMethod {
    GET, HEAD, PUT, DELETE, OPTIONS, POST,
    PROPFIND, MKCOL, MOVE, COPY,
    UNKNOWN;

    companion object {
        fun fromString(raw: String): HttpMethod =
            entries.firstOrNull { it.name == raw.uppercase() } ?: UNKNOWN
    }
}
