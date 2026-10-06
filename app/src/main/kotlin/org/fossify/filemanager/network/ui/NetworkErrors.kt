package org.fossify.filemanager.network.ui

import android.content.Context
import org.fossify.commons.extensions.toast
import org.fossify.filemanager.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.filemanager.network.core.AuthMethod
import org.fossify.filemanager.network.core.AuthenticationFailedException
import org.fossify.filemanager.network.core.ConnectionFailedException
import org.fossify.filemanager.network.core.ConnectionSecrets
import org.fossify.filemanager.network.core.MissingCredentialsException
import org.fossify.filemanager.network.core.OperationCancelledException
import org.fossify.filemanager.network.core.RemoteAccessDeniedException
import org.fossify.filemanager.network.core.RemoteAlreadyExistsException
import org.fossify.filemanager.network.core.RemoteNotFoundException
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.core.UnsupportedRemoteOperationException
import org.fossify.filemanager.network.core.UntrustedServerException
import org.fossify.filemanager.network.data.networkManager

/** Turns network errors into messages and, where the user can fix the problem, into dialogs. */
object NetworkErrors {
    fun describe(context: Context, error: Throwable): String = when (error) {
        is AuthenticationFailedException -> context.getString(R.string.network_error_auth)
        is MissingCredentialsException -> context.getString(R.string.network_error_auth)
        is ConnectionFailedException -> context.getString(R.string.network_error_connection, error.message.orEmpty())
        is RemoteNotFoundException -> context.getString(R.string.network_error_not_found, displayName(error.path))
        is RemoteAccessDeniedException -> context.getString(R.string.network_error_access_denied, displayName(error.path))
        is RemoteAlreadyExistsException -> context.getString(R.string.network_error_exists, displayName(error.path))
        is UnsupportedRemoteOperationException -> context.getString(R.string.network_error_unsupported)
        is OperationCancelledException -> ""
        else -> context.getString(R.string.network_error_generic, error.message ?: error.javaClass.simpleName)
    }

    private fun displayName(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    /**
     * Reports [error] for the drive [connectionId]. Problems the user can resolve are offered as dialogs: trusting
     * a server's identity, entering a password again. After they are resolved [retry] runs the failed action again.
     * Safe to call from any thread.
     */
    fun handle(activity: BaseSimpleActivity, connectionId: String?, error: Throwable, retry: (() -> Unit)? = null) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val manager = activity.networkManager
            val connection = connectionId?.let { manager.repository.get(it) }

            when {
                error is OperationCancelledException -> Unit

                error is UntrustedServerException && connection != null -> {
                    TrustServerDialog(activity, connection.host, error) {
                        manager.repository.setTrustedIdentity(connection.id, error.presentedIdentity)
                        manager.connectionChanged(connection.id)
                        retry?.invoke()
                    }
                }

                (error is MissingCredentialsException || error is AuthenticationFailedException) &&
                    connection != null && connection.authMethod == AuthMethod.PASSWORD -> {
                    PasswordPromptDialog(activity, connection.name, wrong = error is AuthenticationFailedException) { password ->
                        val old = manager.repository.loadSecrets(connection.id) ?: ConnectionSecrets.EMPTY
                        try {
                            manager.repository.save(connection, old.copy(password = password))
                            manager.connectionChanged(connection.id)
                            retry?.invoke()
                        } catch (e: Exception) {
                            activity.toast(R.string.drive_storage_failed)
                        }
                    }
                }

                else -> activity.toast(describe(activity, error))
            }
        }
    }

    fun isRemoteId(path: String) = RemotePath.isRemote(path)
}
