package org.fossify.filemanager.network.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedQueue

/** Requests waiting for [TransferService]. Lives in memory: if the process dies, pending transfers are dropped. */
object TransferQueue {
    private val pending = ConcurrentLinkedQueue<TransferRequest>()

    fun enqueue(context: Context, request: TransferRequest) {
        pending.add(request)
        ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
    }

    internal fun poll(): TransferRequest? = pending.poll()

    internal fun clear() = pending.clear()
}
