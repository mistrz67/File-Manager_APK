package org.fossify.filemanager.network.ui

import android.app.Activity
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.DialogTrustServerBinding
import org.fossify.filemanager.network.core.PinnedIdentity
import org.fossify.filemanager.network.core.UntrustedServerException

/**
 * Asks whether to trust the identity (SSH host key or TLS certificate) a server presented. When the identity
 * differs from a trusted one the warning is much stronger.
 */
class TrustServerDialog(activity: Activity, host: String, error: UntrustedServerException, onTrust: () -> Unit) {
    init {
        val binding = DialogTrustServerBinding.inflate(activity.layoutInflater)
        val presented = PinnedIdentity.parse(error.presentedIdentity)
        val isTls = presented?.type == PinnedIdentity.TYPE_TLS

        binding.trustMessage.text = when {
            error.isChanged -> activity.getString(R.string.trust_server_changed, host)
            isTls -> activity.getString(R.string.trust_server_unknown_tls, host)
            else -> activity.getString(R.string.trust_server_unknown_ssh, host)
        }
        binding.trustFingerprintLabel.text = activity.getString(if (isTls) R.string.trust_server_fingerprint_tls else R.string.trust_server_fingerprint_ssh)
        binding.trustFingerprint.text = presented?.fingerprint ?: error.presentedIdentity

        if (error.isChanged) {
            binding.trustPreviousLabel.beVisible()
            binding.trustPrevious.beVisible()
            binding.trustPrevious.text = PinnedIdentity.parse(error.previousIdentity)?.fingerprint ?: error.previousIdentity
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.trust_server_trust) { _, _ -> onTrust() }
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    binding.root,
                    this,
                    titleId = if (error.isChanged) R.string.trust_server_changed_title else R.string.trust_server_title,
                    cancelOnTouchOutside = false,
                )
            }
    }
}
