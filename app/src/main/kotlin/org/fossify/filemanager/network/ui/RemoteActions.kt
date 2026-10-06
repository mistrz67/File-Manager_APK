package org.fossify.filemanager.network.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.REAL_FILE_PATH
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.ReadTextActivity
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.extensions.openPath
import org.fossify.filemanager.extensions.sharePaths
import org.fossify.filemanager.helpers.OPEN_AS_DEFAULT
import org.fossify.filemanager.helpers.OPEN_AS_TEXT
import org.fossify.filemanager.network.core.CancellationToken
import org.fossify.filemanager.network.core.ConflictPolicy
import org.fossify.filemanager.network.core.OperationCancelledException
import org.fossify.filemanager.network.core.RemoteNotFoundException
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.core.TransferListener
import org.fossify.filemanager.network.core.TransferProgress
import org.fossify.filemanager.network.data.networkManager
import org.fossify.filemanager.network.transfer.TransferQueue
import org.fossify.filemanager.network.transfer.TransferRequest
import java.io.File

/** What the file list does with items on network drives: open, share, rename, delete, copy, move, … */
object RemoteActions {
    const val EXTRA_REMOTE_PATH = "remote_path"

    private const val PROGRESS_DIALOG_DELAY_MS = 400L

    // region open and share

    /** Downloads the file to the cache and opens it: text in the built-in editor, anything else in another app. */
    fun open(activity: BaseSimpleActivity, path: String, forceChooser: Boolean, openAsType: Int = OPEN_AS_DEFAULT, finishActivity: Boolean = false) {
        download(activity, listOf(path)) { files ->
            val file = files.first()
            val mimeType = file.name.getMimeType()
            val isText = mimeType.startsWith("text/") || mimeType == "application/json"
            if (openAsType == OPEN_AS_TEXT || (openAsType == OPEN_AS_DEFAULT && !forceChooser && isText)) {
                activity.startActivity(
                    Intent(activity, ReadTextActivity::class.java).apply {
                        data = Uri.fromFile(file)
                        putExtra(REAL_FILE_PATH, file.absolutePath)
                        putExtra(EXTRA_REMOTE_PATH, path)
                    }
                )
            } else {
                activity.openPath(file.absolutePath, forceChooser, openAsType)
            }
            if (finishActivity) activity.finish()
        }
    }

    fun share(activity: BaseSimpleActivity, items: List<FileDirItem>) {
        val files = items.filter { !it.isDirectory }
        if (files.isEmpty()) {
            activity.toast(R.string.network_error_unsupported)
            return
        }

        download(activity, files.map { it.path }) { local ->
            activity.sharePaths(ArrayList(local.map { it.absolutePath }))
        }
    }

    /**
     * Downloads [paths] into the cache one after another, showing progress if it takes a while, and calls
     * [onDone] on the main thread with the local copies.
     */
    private fun download(activity: BaseSimpleActivity, paths: List<String>, onDone: (List<File>) -> Unit) {
        val manager = activity.networkManager
        val token = CancellationToken()
        val main = Handler(Looper.getMainLooper())
        var dialog: TransferProgressDialog? = null
        var finished = false

        val showDialog = Runnable {
            if (!finished) dialog = TransferProgressDialog(activity, activity.getString(R.string.network_opening_file, RemotePath.name(paths.first()))) { token.cancel() }
        }
        main.postDelayed(showDialog, PROGRESS_DIALOG_DELAY_MS)

        ensureBackgroundThread {
            try {
                val local = paths.map { path ->
                    manager.files.download(path, token, object : TransferListener {
                        override fun onProgress(progress: TransferProgress) {
                            dialog?.update(progress)
                        }
                    })
                }
                main.post {
                    finished = true
                    main.removeCallbacks(showDialog)
                    dialog?.dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) onDone(local)
                }
            } catch (e: Exception) {
                main.post {
                    finished = true
                    main.removeCallbacks(showDialog)
                    dialog?.dismiss()
                }
                if (e !is OperationCancelledException) NetworkErrors.handle(activity, RemotePath.connectionId(paths.first()), e)
            }
        }
    }

    // endregion

    // region changes

    fun rename(activity: BaseSimpleActivity, path: String, onRenamed: (newPath: String) -> Unit) {
        RemoteNameDialog(activity, R.string.rename, RemotePath.name(path), selectBaseNameOnly = true) { newName ->
            if (newName == RemotePath.name(path)) return@RemoteNameDialog
            ensureBackgroundThread {
                try {
                    val renamed = activity.networkManager.files.rename(path, newName)
                    activity.runOnUiThread { onRenamed(renamed) }
                } catch (e: Exception) {
                    NetworkErrors.handle(activity, RemotePath.connectionId(path), e)
                }
            }
        }
    }

    fun createNew(activity: BaseSimpleActivity, parentPath: String, onCreated: () -> Unit) {
        RemoteCreateNewItemDialog(activity, parentPath, onCreated)
    }

    fun properties(activity: BaseSimpleActivity, items: List<FileDirItem>) {
        RemotePropertiesDialog(activity, items)
    }

    fun copyPath(activity: BaseSimpleActivity, path: String) {
        activity.copyToClipboard(NetworkPaths.url(activity, path))
    }

    /** Deletes in the background service; the list is refreshed when it is done. */
    fun delete(activity: BaseSimpleActivity, items: List<FileDirItem>) {
        start(activity, TransferRequest(TransferRequest.Kind.DELETE, items.map { it.path }, destination = null))
    }

    /**
     * Copies or moves [items] to [destination] (a folder on the phone or on a drive), in the background. If items
     * with the same names exist there, asks what to do first.
     */
    fun copyMove(activity: BaseSimpleActivity, items: List<FileDirItem>, destination: String, isCopy: Boolean, preserveModified: Boolean, onStarted: () -> Unit) {
        val kind = if (isCopy) TransferRequest.Kind.COPY else TransferRequest.Kind.MOVE
        val sourceParent = items.first().path.let { if (RemotePath.isRemote(it)) RemotePath.parent(it) else File(it).parent }
        val sameFolder = sourceParent?.trimEnd('/') == destination.trimEnd('/')

        val begin = { policy: ConflictPolicy ->
            start(activity, TransferRequest(kind, items.map { it.path }, destination, policy, preserveModified))
            onStarted()
        }

        if (sameFolder) {
            begin(ConflictPolicy.KEEP_BOTH)
            return
        }

        ensureBackgroundThread {
            val existing: Set<String> = try {
                existingNames(activity, destination)
            } catch (e: RemoteNotFoundException) {
                emptySet()
            } catch (e: Exception) {
                NetworkErrors.handle(activity, RemotePath.connectionId(destination) ?: RemotePath.connectionId(items.first().path), e)
                return@ensureBackgroundThread
            }

            activity.runOnUiThread {
                if (items.any { it.name in existing }) {
                    ConflictDialog(activity) { begin(it) }
                } else {
                    begin(ConflictPolicy.KEEP_BOTH)
                }
            }
        }
    }

    private fun existingNames(activity: BaseSimpleActivity, folder: String): Set<String> {
        return if (RemotePath.isRemote(folder)) {
            activity.networkManager.files.list(folder).mapTo(HashSet()) { it.name }
        } else {
            File(folder).list()?.toSet() ?: emptySet()
        }
    }

    private fun start(activity: BaseSimpleActivity, request: TransferRequest) {
        activity.handleNotificationPermission {
            // the transfer runs either way; without the permission there is just no notification to follow it
            TransferQueue.enqueue(activity, request)
            activity.toast(R.string.transfer_started, Toast.LENGTH_SHORT)
        }
    }

    // endregion
}
