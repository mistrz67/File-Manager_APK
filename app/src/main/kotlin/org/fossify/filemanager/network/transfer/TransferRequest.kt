package org.fossify.filemanager.network.transfer

import org.fossify.filemanager.network.core.ConflictPolicy
import java.util.concurrent.atomic.AtomicLong

/**
 * A file operation handed to [TransferService]. Paths are the ones the UI uses: absolute paths on the phone or
 * `remote://` paths on a network drive. All [sources] live on the same drive (or all on the phone).
 */
data class TransferRequest(
    val kind: Kind,
    val sources: List<String>,
    /** Destination folder; null for [Kind.DELETE]. */
    val destination: String?,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    val preserveModified: Boolean = true,
    val id: Long = nextId.incrementAndGet(),
) {
    enum class Kind { COPY, MOVE, DELETE }

    private companion object {
        val nextId = AtomicLong()
    }
}
