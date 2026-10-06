package org.fossify.filemanager.network.ui

import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.filemanager.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.databinding.DialogRemotePropertiesBinding
import org.fossify.filemanager.network.core.CancellationToken
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.networkManager

/** Properties of items on a network drive; the size of folders is calculated in the background. */
class RemotePropertiesDialog(private val activity: BaseSimpleActivity, items: List<FileDirItem>) {
    private val binding = DialogRemotePropertiesBinding.inflate(activity.layoutInflater)
    private val token = CancellationToken()

    init {
        val paths = items.map { it.path }
        val single = items.singleOrNull()

        binding.propertiesName.text = single?.name ?: activity.resources.getQuantityString(R.plurals.items, items.size, items.size)
        binding.propertiesLocation.text = NetworkPaths.label(activity, RemotePath.parent(paths.first()) ?: paths.first())

        if (single != null && !single.isDirectory) {
            binding.propertiesSize.text = single.size.formatSize()
            binding.propertiesFilesLabel.beGone()
            binding.propertiesFiles.beGone()
        } else {
            binding.propertiesSize.text = activity.getString(R.string.network_calculating)
            binding.propertiesFiles.text = activity.getString(R.string.network_calculating)
            ensureBackgroundThread {
                try {
                    val (size, files) = activity.networkManager.files.measure(paths, token)
                    activity.runOnUiThread {
                        binding.propertiesSize.text = size.formatSize()
                        binding.propertiesFiles.text = files.toString()
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        binding.propertiesSize.text = "—"
                        binding.propertiesFiles.text = "—"
                    }
                }
            }
        }

        if (single != null && single.modified > 0) {
            binding.propertiesModified.text = single.modified.formatDate(activity)
        } else {
            binding.propertiesModifiedLabel.beGone()
            binding.propertiesModified.beGone()
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener { token.cancel() }
            .apply { activity.setupDialogStuff(binding.root, this, titleId = R.string.properties) }
    }
}
