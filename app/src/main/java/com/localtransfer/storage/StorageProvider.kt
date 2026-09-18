package com.localtransfer.storage

import java.io.InputStream
import java.io.OutputStream

data class StorageEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,       // 0 for directories
    val lastModified: Long // epoch millis
)

class StorageNotFoundException(path: String) : Exception("Not found: $path")
class StorageAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Filesystem-shaped abstraction consumed by the HTTP/WebDAV layer. Paths are always
 * '/'-separated, relative to whatever root the provider was constructed with, and
 * MUST be validated by the caller (router) against traversal before reaching here --
 * providers additionally defend themselves since they may be reused outside the router.
 */
interface StorageProvider {
    fun list(path: String): List<StorageEntry>
    fun stat(path: String): StorageEntry?
    fun exists(path: String): Boolean

    /** [rangeStart] allows resuming/streaming partial reads (HTTP Range support)
     *  without loading the file into memory. */
    fun openRead(path: String, rangeStart: Long = 0L): InputStream

    /** Truncates and opens for writing (uploads always replace by default, matching PUT semantics). */
    fun openWrite(path: String): OutputStream

    fun delete(path: String): Boolean
    fun mkdir(path: String): Boolean
}
