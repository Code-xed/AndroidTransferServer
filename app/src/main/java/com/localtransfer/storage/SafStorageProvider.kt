package com.localtransfer.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * StorageProvider backed by a user-granted SAF tree URI (persisted permission).
 *
 * Honest limitations, documented rather than hidden:
 *  - list()/resolve() do a linear scan of DocumentFile children per path segment.
 *    DocumentsProvider has no path-based lookup API, only URI/child enumeration, so
 *    deep directories cost O(depth * childrenPerDir) per request. Fine for a phone's
 *    shared-folder use case; would need a name->URI cache for very large trees.
 *  - Range reads use ParcelFileDescriptor + FileChannel.position() for a real seek
 *    when the backing provider exposes a real file descriptor (true for local storage
 *    and most DocumentsProvider implementations on-device). If a provider ever hands
 *    back a non-seekable pipe fd, position() throws and we fall back to skip(), which
 *    is correct but O(n) for large offsets -- documented, not hidden.
 */
class SafStorageProvider(
    private val context: Context,
    private val treeUri: Uri
) : StorageProvider {

    private val resolver get() = context.contentResolver

    private fun rootDoc(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw StorageAccessException("Cannot open tree: $treeUri")

    private fun resolveDoc(path: String): DocumentFile? {
        val segments = path.trim('/').split("/").filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw StorageAccessException("Path traversal rejected: $path")
        var current = rootDoc()
        for (segment in segments) {
            current = current.listFiles().firstOrNull { it.name == segment } ?: return null
        }
        return current
    }

    private fun resolveParentAndName(path: String): Pair<DocumentFile, String> {
        val trimmed = path.trim('/')
        val idx = trimmed.lastIndexOf('/')
        val parentPath = if (idx >= 0) trimmed.substring(0, idx) else ""
        val name = if (idx >= 0) trimmed.substring(idx + 1) else trimmed
        require(name.isNotBlank()) { "Empty file name" }
        val parent = if (parentPath.isEmpty()) rootDoc() else resolveDoc(parentPath)
            ?: throw StorageNotFoundException(parentPath)
        return parent to name
    }

    override fun list(path: String): List<StorageEntry> {
        val dir = if (path.trim('/').isEmpty()) rootDoc() else (resolveDoc(path) ?: return emptyList())
        return dir.listFiles().map {
            StorageEntry(it.name ?: "unnamed", it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified())
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun stat(path: String): StorageEntry? {
        val doc = if (path.trim('/').isEmpty()) rootDoc() else resolveDoc(path) ?: return null
        return StorageEntry(doc.name ?: "unnamed", doc.isDirectory, if (doc.isDirectory) 0L else doc.length(), doc.lastModified())
    }

    override fun exists(path: String): Boolean =
        if (path.trim('/').isEmpty()) true else resolveDoc(path) != null

    override fun openRead(path: String, rangeStart: Long): InputStream {
        val doc = resolveDoc(path) ?: throw StorageNotFoundException(path)
        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(doc.uri, "r")
            ?: throw StorageAccessException("Provider returned no descriptor for $path")
        val stream = FileInputStream(pfd.fileDescriptor)
        if (rangeStart > 0) {
            try {
                stream.channel.position(rangeStart)
            } catch (e: IOException) {
                // Non-seekable underlying descriptor -- fall back to a correct, if
                // slower, sequential skip so Range requests still succeed.
                var toSkip = rangeStart
                while (toSkip > 0) {
                    val skipped = stream.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
            }
        }
        return stream
    }

    override fun openWrite(path: String): OutputStream {
        val (parent, name) = resolveParentAndName(path)
        val existing = parent.listFiles().firstOrNull { it.name == name }
        val doc = existing ?: parent.createFile(guessMime(name), name)
            ?: throw StorageAccessException("Failed to create file: $path")
        // "wt" = write + truncate, so re-uploading the same filename replaces its content
        // rather than appending or leaving trailing old bytes.
        return resolver.openOutputStream(doc.uri, "wt")
            ?: throw StorageAccessException("Provider returned no output stream for $path")
    }

    override fun delete(path: String): Boolean {
        val doc = resolveDoc(path) ?: return false
        return doc.delete()
    }

    override fun mkdir(path: String): Boolean {
        val (parent, name) = resolveParentAndName(path)
        if (parent.listFiles().any { it.name == name }) return true
        return parent.createDirectory(name) != null
    }

    private fun guessMime(name: String): String =
        com.localtransfer.server.MimeTypes.forFileName(name)
}
