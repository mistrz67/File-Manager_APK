package org.fossify.filemanager.network.ui

import android.app.Activity
import android.view.View
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.DialogRemoteNameBinding

/** Asks for a file or folder name (used to rename items on a network drive and to create new folders). */
class RemoteNameDialog(
    activity: Activity,
    titleId: Int,
    initialName: String = "",
    selectBaseNameOnly: Boolean = false,
    onName: (String) -> Unit,
) {
    init {
        val binding = DialogRemoteNameBinding.inflate(activity.layoutInflater)
        binding.remoteNameInput.setText(initialName)
        if (selectBaseNameOnly && initialName.contains('.') && !initialName.startsWith(".")) {
            binding.remoteNameInput.setSelection(0, initialName.lastIndexOf('.'))
        } else {
            binding.remoteNameInput.setSelection(0, initialName.length)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = titleId) { dialog ->
                    dialog.showKeyboard(binding.remoteNameInput)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener {
                        val name = binding.remoteNameInput.value
                        when {
                            name.isEmpty() -> activity.toast(R.string.empty_name)
                            !name.isAValidFilename() -> activity.toast(R.string.invalid_name)
                            else -> {
                                dialog.dismiss()
                                onName(name)
                            }
                        }
                    })
                }
            }
    }
}
