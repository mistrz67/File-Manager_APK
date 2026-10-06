package org.fossify.filemanager.network.ui

import android.view.View
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.filemanager.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.databinding.DialogCreateNewBinding
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.networkManager

/** Creates a new folder or empty file in a folder of a network drive. [onCreated] runs on success only. */
class RemoteCreateNewItemDialog(
    private val activity: BaseSimpleActivity,
    private val parentPath: String,
    private val onCreated: () -> Unit,
) {
    private val binding = DialogCreateNewBinding.inflate(activity.layoutInflater)

    init {
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.create_new) { dialog ->
                    dialog.showKeyboard(binding.itemTitle)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener {
                        val name = binding.itemTitle.value
                        when {
                            name.isEmpty() -> activity.toast(R.string.empty_name)
                            !name.isAValidFilename() -> activity.toast(R.string.invalid_name)
                            else -> {
                                val isFolder = binding.dialogRadioGroup.checkedRadioButtonId == R.id.dialog_radio_directory
                                dialog.dismiss()
                                create(name, isFolder)
                            }
                        }
                    })
                }
            }
    }

    private fun create(name: String, isFolder: Boolean) {
        ensureBackgroundThread {
            try {
                val files = activity.networkManager.files
                if (isFolder) files.createFolder(parentPath, name) else files.createFile(parentPath, name)
                activity.runOnUiThread { onCreated() }
            } catch (e: Exception) {
                NetworkErrors.handle(activity, RemotePath.connectionId(parentPath), e)
            }
        }
    }
}
