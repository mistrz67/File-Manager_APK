package org.fossify.filemanager.network.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beInvisible
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.filemanager.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.databinding.DialogRemoteFolderPickerBinding
import org.fossify.filemanager.databinding.ItemRemoteFolderBinding
import org.fossify.filemanager.network.core.RemoteEntry
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.networkManager

/** Lets the user pick a folder on a network drive. [startPath] is a `remote://` path, or null for the drive's default folder. */
class RemoteFolderPickerDialog(
    private val activity: BaseSimpleActivity,
    private val connectionId: String,
    startPath: String?,
    private val onPicked: (String) -> Unit,
) {
    private val binding = DialogRemoteFolderPickerBinding.inflate(activity.layoutInflater)
    private val manager = activity.networkManager
    private var currentPath = startPath ?: RemotePath.build(connectionId, "/")
    private var dialog: AlertDialog? = null
    private var loadGeneration = 0
    private val adapter = FolderAdapter()

    init {
        binding.folderPickerList.adapter = adapter
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.network_select_this_folder, null)
            .setNeutralButton(R.string.network_new_folder, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = R.string.network_choose_folder) { alert ->
                    dialog = alert
                    alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        alert.dismiss()
                        onPicked(currentPath)
                    }
                    alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { createFolder() }
                }
            }

        if (startPath == null) {
            loadInitialFolder()
        } else {
            load(currentPath)
        }
    }

    private fun loadInitialFolder() {
        showLoading(true)
        ensureBackgroundThread {
            try {
                val initial = manager.files.initialPath(connectionId)
                activity.runOnUiThread {
                    currentPath = initial
                    load(initial)
                }
            } catch (e: Exception) {
                failed(e) { loadInitialFolder() }
            }
        }
    }

    private fun load(path: String) {
        currentPath = path
        binding.folderPickerPath.text = NetworkPaths.label(activity, path)
        showLoading(true)
        val generation = ++loadGeneration
        ensureBackgroundThread {
            try {
                val folders = manager.files.list(path)
                    .filter { it.isDirectory }
                    .sortedBy { it.name.lowercase() }
                activity.runOnUiThread {
                    if (generation == loadGeneration) show(folders)
                }
            } catch (e: Exception) {
                if (generation == loadGeneration) failed(e) { load(path) }
            }
        }
    }

    private fun show(folders: List<RemoteEntry>) {
        showLoading(false)
        adapter.submit(folders, canGoUp = !RemotePath.isRoot(currentPath))
        binding.folderPickerPlaceholder.apply {
            text = context.getString(R.string.network_folder_empty)
            setTextColor(activity.getProperTextColor())
            beVisibleIf(folders.isEmpty())
        }
    }

    private fun failed(error: Exception, retry: () -> Unit) {
        activity.runOnUiThread {
            showLoading(false)
            adapter.submit(emptyList(), canGoUp = !RemotePath.isRoot(currentPath))
            binding.folderPickerPlaceholder.apply {
                text = context.getString(R.string.network_folder_loading_failed)
                beVisible()
            }
        }
        NetworkErrors.handle(activity, connectionId, error, retry)
    }

    private fun showLoading(loading: Boolean) {
        if (loading) binding.folderPickerProgress.beVisible() else binding.folderPickerProgress.beInvisible()
        if (loading) binding.folderPickerPlaceholder.beGone()
    }

    private fun createFolder() {
        RemoteNameDialog(activity, R.string.network_new_folder) { name ->
            ensureBackgroundThread {
                try {
                    val created = manager.files.createFolder(currentPath, name)
                    activity.runOnUiThread { load(created) }
                } catch (e: Exception) {
                    NetworkErrors.handle(activity, connectionId, e)
                }
            }
        }
    }

    private inner class FolderAdapter : RecyclerView.Adapter<FolderAdapter.Holder>() {
        private var folders: List<RemoteEntry> = emptyList()
        private var canGoUp = false

        fun submit(newFolders: List<RemoteEntry>, canGoUp: Boolean) {
            folders = newFolders
            this.canGoUp = canGoUp
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = folders.size + if (canGoUp) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRemoteFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val isUp = canGoUp && position == 0
            val entry = if (canGoUp) folders.getOrNull(position - 1) else folders.getOrNull(position)
            holder.binding.apply {
                remoteFolderName.text = if (isUp) ".." else entry?.name
                remoteFolderName.setTextColor(activity.getProperTextColor())
                remoteFolderIcon.applyColorFilter(activity.getProperPrimaryColor())
                root.setOnClickListener {
                    if (isUp) {
                        RemotePath.parent(currentPath)?.let { load(it) }
                    } else if (entry != null) {
                        load(RemotePath.child(currentPath, entry.name))
                    }
                }
            }
        }

        inner class Holder(val binding: ItemRemoteFolderBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
