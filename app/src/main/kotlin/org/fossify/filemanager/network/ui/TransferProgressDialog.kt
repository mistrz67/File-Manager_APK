package org.fossify.filemanager.network.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.DialogTransferProgressBinding
import org.fossify.filemanager.network.core.TransferProgress

/** Small blocking dialog with a progress bar and a cancel button, shown while one file is downloaded to be opened. */
class TransferProgressDialog(
    private val activity: Activity,
    title: String,
    private val onCancel: () -> Unit,
) {
    private val binding = DialogTransferProgressBinding.inflate(activity.layoutInflater)
    private var dialog: AlertDialog? = null

    init {
        binding.transferProgressTitle.text = title
        activity.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .apply {
                activity.setupDialogStuff(binding.root, this, cancelOnTouchOutside = false) { alert ->
                    dialog = alert
                    alert.setOnCancelListener { onCancel() }
                }
            }
    }

    fun update(progress: TransferProgress) {
        activity.runOnUiThread {
            if (progress.bytesTotal > 0) {
                binding.transferProgressBar.isIndeterminate = false
                binding.transferProgressBar.max = PERCENT_STEPS
                binding.transferProgressBar.progress = (progress.bytesDone * PERCENT_STEPS / progress.bytesTotal).toInt()
                binding.transferProgressDetails.text = "${progress.bytesDone.formatSize()} / ${progress.bytesTotal.formatSize()}"
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) dialog?.dismiss()
        }
    }

    private companion object {
        const val PERCENT_STEPS = 1000
    }
}
