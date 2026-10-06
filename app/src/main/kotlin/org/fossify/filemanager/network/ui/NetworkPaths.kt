package org.fossify.filemanager.network.ui

import android.content.Context
import org.fossify.filemanager.network.core.Paths
import org.fossify.filemanager.network.core.Protocol
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.networkManager

/** Human readable forms of `remote://` paths. */
object NetworkPaths {
    /** `NAS/Documents/report.pdf` — the drive name followed by the path inside it. */
    fun label(context: Context, remotePath: String): String {
        val parsed = RemotePath.parse(remotePath) ?: return remotePath
        val drive = driveName(context, parsed.connectionId)
        return if (parsed.innerPath == "/") drive else "$drive${parsed.innerPath}"
    }

    fun driveName(context: Context, connectionId: String): String {
        return context.networkManager.repository.get(connectionId)?.name ?: connectionId
    }

    /** A URL such as `smb://nas/Public/Documents/report.pdf`, suitable for copying to the clipboard. */
    fun url(context: Context, remotePath: String): String {
        val parsed = RemotePath.parse(remotePath) ?: return remotePath
        val connection = context.networkManager.repository.get(parsed.connectionId) ?: return remotePath
        val base = connection.displayAddress()
        // for SMB the share is part of the address already; the inner path follows it
        val inner = if (connection.protocol == Protocol.SMB || connection.protocol.isFtpFamily || connection.protocol == Protocol.SFTP) {
            Paths.normalize(parsed.innerPath)
        } else {
            parsed.innerPath
        }
        return if (inner == "/") base else "$base$inner"
    }
}
