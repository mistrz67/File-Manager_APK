package org.fossify.filemanager.network.transfer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import org.fossify.commons.helpers.isQPlus
import org.fossify.filemanager.R
import org.fossify.filemanager.network.core.CancellationToken
import org.fossify.filemanager.network.core.RemoteException
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.core.TransferFailure
import org.fossify.filemanager.network.core.TransferJob
import org.fossify.filemanager.network.core.TransferListener
import org.fossify.filemanager.network.core.TransferMode
import org.fossify.filemanager.network.core.TransferProgress
import org.fossify.filemanager.network.core.TransferResult
import org.fossify.filemanager.network.data.networkManager
import org.fossify.filemanager.network.ui.NetworkPaths
import java.util.concurrent.Executors

/**
 * Runs queued copy, move and delete operations on network drives in the background, showing progress in a
 * notification that can cancel the running operation. Operations are executed one after another.
 */
class TransferService : Service() {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "network-drive-transfer") }
    private val lock = Any()
    private var workerRunning = false
    private var lastStartId = 0

    @Volatile
    private var currentToken: CancellationToken? = null

    private lateinit var notifications: TransferNotifications
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        notifications = TransferNotifications(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a foreground service must call startForeground() promptly after being started this way
        startInForeground(notifications.progress(getString(R.string.transfer_preparing), "", -1))

        if (intent?.action == ACTION_CANCEL) {
            TransferQueue.clear()
            currentToken?.cancel()
        }

        synchronized(lock) {
            lastStartId = startId
            if (!workerRunning) {
                workerRunning = true
                executor.execute { workLoop() }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits data sync services to a few hours a day; stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        TransferQueue.clear()
        currentToken?.cancel()
    }

    override fun onDestroy() {
        releaseLocks()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startInForeground(notification: android.app.Notification) {
        try {
            if (isQPlus()) {
                ServiceCompat.startForeground(this, TransferNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(TransferNotifications.ONGOING_ID, notification)
            }
        } catch (e: Exception) {
            // not allowed to run in the foreground right now; the work continues as a regular service
        }
    }

    private fun workLoop() {
        acquireLocks()
        try {
            while (true) {
                val request = synchronized(lock) {
                    TransferQueue.poll().also { if (it == null) workerRunning = false } ?: return@synchronized null
                } ?: break

                val result = execute(request)
                notifications.finished(request, result)
                TransferEvents.dispatch(request, result)
            }
        } finally {
            releaseLocks()
            synchronized(lock) {
                // a request that arrived meanwhile has started another loop (and bumped lastStartId); leave it running
                if (!workerRunning) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }

    private fun execute(request: TransferRequest): TransferResult {
        val manager = networkManager
        val token = CancellationToken()
        currentToken = token
        val isDelete = request.kind == TransferRequest.Kind.DELETE
        val title = when (request.kind) {
            TransferRequest.Kind.COPY -> getString(R.string.transfer_copying, locationName(request.destination))
            TransferRequest.Kind.MOVE -> getString(R.string.transfer_moving, locationName(request.destination))
            TransferRequest.Kind.DELETE -> getString(R.string.transfer_deleting, locationName(request.sources.first()))
        }
        notifications.update(notifications.progress(title, getString(R.string.transfer_preparing), -1))

        var lastUpdate = 0L
        val listener = object : TransferListener {
            override fun onScanning(filesFound: Int) {
                throttled { notifications.update(notifications.progress(title, getString(R.string.transfer_scanning, filesFound), -1)) }
            }

            override fun onProgress(progress: TransferProgress) {
                throttled {
                    val (text, percent) = notifications.describeProgress(progress, isDelete)
                    notifications.update(notifications.progress(title, text, percent))
                }
            }

            private fun throttled(action: () -> Unit) {
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= NOTIFICATION_INTERVAL_MS) {
                    lastUpdate = now
                    action()
                }
            }
        }

        return try {
            if (isDelete) {
                manager.engine.delete(manager.endpointFor(request.sources.first()), request.sources.map { innerPath(it) }, token, listener)
            } else {
                val job = TransferJob(
                    mode = if (request.kind == TransferRequest.Kind.MOVE) TransferMode.MOVE else TransferMode.COPY,
                    sources = request.sources.map { innerPath(it) },
                    source = manager.endpointFor(request.sources.first()),
                    destination = manager.endpointFor(request.destination!!),
                    destinationDir = innerPath(request.destination),
                    conflictPolicy = request.conflictPolicy,
                    preserveModified = request.preserveModified,
                )
                manager.engine.run(job, token, listener)
            }
        } catch (e: Exception) {
            TransferResult(0, 0, listOf(TransferFailure(request.sources.firstOrNull().orEmpty(), if (e is RemoteException) e else RemoteException(e.message, e))), cancelled = false, bytes = 0)
        } finally {
            currentToken = null
        }
    }

    private fun innerPath(path: String) = if (RemotePath.isRemote(path)) RemotePath.innerPath(path) else path

    private fun locationName(path: String?): String = when {
        path == null -> ""
        RemotePath.isRemote(path) -> NetworkPaths.label(this, path)
        else -> path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
    }

    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fossify.filemanager:transfer").apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MS)
            }
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val mode = if (isQPlus()) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi?.createWifiLock(mode, "fossify.filemanager:transfer")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // locks only keep long transfers alive while the screen is off
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_CANCEL = "org.fossify.filemanager.action.CANCEL_TRANSFER"
        private const val NOTIFICATION_INTERVAL_MS = 700L
        private const val MAX_LOCK_MS = 6 * 60 * 60 * 1000L
    }
}
