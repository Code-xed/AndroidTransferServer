package com.localtransfer.server

import java.io.InputStream

/**
 * Wraps the shared connection InputStream so a handler reading a PUT body can never
 * consume bytes belonging to the *next* pipelined/keep-alive request. Does NOT close
 * the underlying socket stream on close() -- the connection loop owns that lifecycle.
 */
class BoundedInputStream(
    private val delegate: InputStream,
    private var remaining: Long
) : InputStream() {

    val bytesLeft: Long get() = remaining

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = delegate.read(b, off, toRead)
        if (n > 0) remaining -= n
        return n
    }

    /** Drain any bytes the handler didn't read, so the connection stream is left
     *  positioned exactly at the start of the next request. */
    fun drainRemaining() {
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = read(buf, 0, buf.size)
            if (n < 0) break
        }
    }

    override fun close() { /* intentionally does not close the socket stream */ }
}
