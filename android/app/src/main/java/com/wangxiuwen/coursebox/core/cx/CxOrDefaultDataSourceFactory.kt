package com.wangxiuwen.coursebox.core.cx

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * Routes ExoPlayer DataSource requests by URI scheme.
 *   - `cx://...` → [CxDataSource] (random-access slice of a .cx archive)
 *   - everything else → [DefaultDataSource] (file / http / https / asset / ...)
 *
 * Each `createDataSource()` returns a fresh dispatcher; the underlying
 * source is chosen on `open()` once the DataSpec URI is known. Transfer
 * listeners registered before open() get re-applied to the chosen
 * delegate.
 *
 * BaseDataSource's addTransferListener is final, so we implement
 * DataSource directly and proxy everything to the delegate.
 */
@UnstableApi
class CxOrDefaultDataSourceFactory(private val ctx: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = Dispatcher(ctx)

    private class Dispatcher(ctx: Context) : DataSource {
        private val cxFactory = CxDataSource.Factory()
        private val defaultFactory = DefaultDataSource.Factory(ctx)
        private var delegate: DataSource? = null
        private val pendingListeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            pendingListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val chosen = if (dataSpec.uri.scheme == CxDataSource.SCHEME) {
                cxFactory.createDataSource()
            } else {
                defaultFactory.createDataSource()
            }
            for (l in pendingListeners) chosen.addTransferListener(l)
            delegate = chosen
            return chosen.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val d = delegate ?: throw java.io.IOException("read before open")
            return d.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun close() {
            try { delegate?.close() } finally { delegate = null }
        }

        override fun getResponseHeaders(): MutableMap<String, MutableList<String>> =
            delegate?.responseHeaders ?: mutableMapOf()
    }
}
