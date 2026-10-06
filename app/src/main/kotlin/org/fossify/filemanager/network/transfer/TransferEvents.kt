package org.fossify.filemanager.network.transfer

import android.os.Handler
import android.os.Looper
import org.fossify.filemanager.network.core.TransferResult
import java.util.concurrent.CopyOnWriteArrayList

/** Lets screens learn that a background transfer finished (to refresh the folder they show). */
object TransferEvents {
    fun interface Listener {
        fun onTransferFinished(request: TransferRequest, result: TransferResult)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun register(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    internal fun dispatch(request: TransferRequest, result: TransferResult) {
        main.post { listeners.forEach { it.onTransferFinished(request, result) } }
    }
}
