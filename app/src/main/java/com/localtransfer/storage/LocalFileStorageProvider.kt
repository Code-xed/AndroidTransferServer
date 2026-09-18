package com.localtransfer.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * StorageProvider backed directly by java.io.File under [root]. Used for the app's
 * own web root (bundled assets copied to internal storage) and is a fine default for
 * devices/paths where SAF isn't required. Every path is resolved and re-validated
 * against [root]'s canonical path to make directory-traversal escape impossible even
 * if a caller upstream forgot to sanitize.
 */
class LocalFileStorageProvider(private val root: File) : StorageProvider {

    init {
        require(root.exists() && root.isDirectory) { "Root must be an existing directory: $root" }
    }

    private val canonicalRoot: String = root.canonicalFile.path

    private fun resolve(path: String): File {
        val cleaned = path.trim('/').split("/").filter { it.isNotEmpty() && it != "." }
        if (cleaned.any { it == ".." }) throw StorageAccessException("Path traversal rejected: $path")
        var f = root
        for (segment in cleaned) f = File(f, segment)
        val canonical = f.canonicalFile.path
        if (canonical != canonicalRoot && !canonical.startsWith(canonicalRoot + File.separator)) {
            throw StorageAccessException("Resolved path escapes storage root: $path")
        }
        return f
    }

    override fun list(path: String): List<StorageEntry> {
        val dir = resolve(path)
        val children = dir.listFiles() ?: return emptyList()
        return children.map {
            StorageEntry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified())
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun stat(path: String): StorageEntry? {
        val f = resolve(path)
        if (!f.exists()) return null
        return StorageEntry(f.name, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified())
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun openRead(path: String, rangeStart: Long): InputStream {
        val f = resolve(path)
        if (!f.exists() || f.isDirectory) throw StorageNotFoundException(path)
        val stream = FileInputStream(f)
        if (rangeStart > 0) stream.channel.position(rangeStart)
        return stream
    }

    override fun openWrite(path: String): OutputStream {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        return FileOutputStream(f, false)
    }

    override fun delete(path: String): Boolean {
        val f = resolve(path)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    override fun mkdir(path: String): Boolean = resolve(path).mkdirs()
}
