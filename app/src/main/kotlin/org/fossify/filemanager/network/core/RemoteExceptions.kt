package org.fossify.filemanager.network.core

import java.io.IOException

/** Base class of all errors raised by the network drive layer. */
open class RemoteException(message: String?, cause: Throwable? = null) : IOException(message, cause)

class ConnectionFailedException(message: String?, cause: Throwable? = null) : RemoteException(message, cause)

class AuthenticationFailedException(message: String?, cause: Throwable? = null) : RemoteException(message, cause)

/** Thrown when a connection needs a password or key that is not available (e.g. after restoring a backup). */
class MissingCredentialsException(val connectionId: String) : RemoteException("Missing credentials for $connectionId")

class RemoteNotFoundException(val path: String, cause: Throwable? = null) : RemoteException("Not found: $path", cause)

class RemoteAlreadyExistsException(val path: String, cause: Throwable? = null) : RemoteException("Already exists: $path", cause)

class RemoteAccessDeniedException(val path: String, cause: Throwable? = null) : RemoteException("Access denied: $path", cause)

class RemoteNotEmptyException(val path: String, cause: Throwable? = null) : RemoteException("Directory not empty: $path", cause)

class UnsupportedRemoteOperationException(message: String) : RemoteException(message)

class OperationCancelledException : RemoteException("Cancelled")

/**
 * The server presented an identity (SSH host key or TLS certificate) that is not trusted yet
 * ([previousIdentity] == null) or that differs from the one the user trusted before.
 */
class UntrustedServerException(
    val host: String,
    /** What was presented, in [PinnedIdentity] serialized form. */
    val presentedIdentity: String,
    /** What the user trusted before, or null when the server is seen for the first time. */
    val previousIdentity: String?,
) : RemoteException(
    if (previousIdentity == null) "Untrusted server identity of $host" else "Server identity of $host has changed"
) {
    val isChanged: Boolean get() = previousIdentity != null
}
