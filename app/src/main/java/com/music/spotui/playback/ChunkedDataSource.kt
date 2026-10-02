package com.music.spotui.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.Locale

/**
 * Bounded-range DataSource wrapper for GoogleVideo streams.
 *
 * GoogleVideo CDN throttles or immediately rejects (HTTP 403) unbounded requests (len=-1).
 * By reading the total file size from `clen` and chunking the transfer into bounded Range slices
 * (512 KiB for ANDROID_VR/TVHTML5, 1 MiB for standard clients), each chunk is served at full line rate
 * and never triggers open-ended request blocks.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
) : DataSource {
    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var passthrough = false
    private var rangeBytes = chunkBytes

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        close()
        baseSpec = dataSpec
        position = dataSpec.position
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (total == null) {
            // Non-googlevideo or streams without clen are passed through directly.
            passthrough = true
            chunkOpen = true
            return upstream.open(dataSpec)
        }

        passthrough = false
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            total
        } else {
            minOf(total, position + dataSpec.length)
        }
        bytesRemaining = (end - position).coerceAtLeast(0L)
        rangeBytes = minOf(chunkBytes, rangeBytesFor(dataSpec.uri))
        if (bytesRemaining > 0) {
            openChunk()
        }
        return bytesRemaining
    }

    private fun openChunk() {
        val length = minOf(rangeBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        try {
            upstream.open(spec)
            chunkRemaining = length
            chunkOpen = true
        } catch (error: Exception) {
            val client = spec.uri.getQueryParameter("c") ?: "unknown"
            Log.w(TAG, "Range $position-${position + length - 1} refused for client $client: ${error.message}")
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (chunkRemaining == 0L) {
            closeChunk()
            openChunk()
        }
        val toRead = minOf(length.toLong(), chunkRemaining).toInt()
        val read = upstream.read(buffer, offset, toRead)
        if (read != C.RESULT_END_OF_INPUT) {
            position += read
            chunkRemaining -= read
            bytesRemaining -= read
            return read
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun closeChunk() {
        if (chunkOpen) {
            runCatching { upstream.close() }
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        if (passthrough) {
            runCatching { upstream.close() }
            passthrough = false
        }
        baseSpec = null
        position = 0L
        bytesRemaining = 0L
        chunkRemaining = 0L
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }

    companion object {
        private const val TAG = "ChunkedDataSource"
        const val DEFAULT_CHUNK_BYTES = 1024L * 1024 // 1 MiB

        fun rangeBytesFor(uri: Uri): Long {
            val host = uri.host ?: return Long.MAX_VALUE
            if (!host.endsWith("googlevideo.com")) return Long.MAX_VALUE
            val client = uri.getQueryParameter("c")?.uppercase(Locale.ROOT)
            return if (client == "ANDROID_VR" || client?.startsWith("TVHTML5_SIMPLY") == true) {
                512L * 1024 // 512 KiB
            } else {
                1024L * 1024 // 1 MiB
            }
        }

        fun rangeBytesFor(url: String): Long =
            runCatching { rangeBytesFor(Uri.parse(url)) }.getOrDefault(DEFAULT_CHUNK_BYTES)
    }
}
