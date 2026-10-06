package org.fossify.filemanager.network.core

object RemoteClientFactory {
    fun create(config: ConnectionConfig, options: ClientOptions = ClientOptions()): RemoteClient {
        return when (config.connection.protocol) {
            Protocol.SMB -> SmbRemoteClient(config, options)
            Protocol.SFTP -> SftpRemoteClient(config, options)
            Protocol.FTP, Protocol.FTPES, Protocol.FTPS -> FtpRemoteClient(config, options)
        }
    }
}
