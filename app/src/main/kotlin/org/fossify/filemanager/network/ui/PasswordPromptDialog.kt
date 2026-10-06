package org.fossify.filemanager.network.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.value
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.DialogPasswordPromptBinding

/** Asks for the password of a drive whose saved password is missing or was rejected. */
class PasswordPromptDialog(activity: Activity, driveName: String, wrong: Boolean, onPassword: (String) -> Unit) {
    init {
        val binding = DialogPasswordPromptBinding.inflate(activity.layoutInflater)
        binding.passwordPromptMessage.text = activity.getString(if (wrong) R.string.password_wrong else R.string.password_required, driveName)

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleText = driveName) { dialog ->
                    dialog.showKeyboard(binding.passwordPromptInput)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = binding.passwordPromptInput.text?.toString().orEmpty()
                        dialog.dismiss()
                        onPassword(password)
                    }
                }
            }
    }
}
