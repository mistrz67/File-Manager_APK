package org.fossify.filemanager.network.core

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * [FTPSClient] that resumes the control connection's TLS session on passive data connections.
 *
 * Servers such as vsftpd (by default) and FileZilla Server refuse data connections that do not resume the TLS
 * session of the control connection. TLS stacks find resumable sessions by peer host and port, and a data
 * connection goes to another port, so the stock client never resumes. Here the data socket is layered over the
 * plain connection using the control connection's host and port as its identity, which makes the cached session
 * be found. Active mode keeps the stock behaviour, and so does a TLS stack that cannot work with the wrapped
 * socket: the first failed handshake switches this client back to the stock data connections.
 */
internal class SessionResumingFtpsClient(
    isImplicit: Boolean,
    private val sslContext: SSLContext,
    private val controlHost: String,
    private val controlPort: Int,
    private val dataTimeoutMs: Int,
) : FTPSClient(isImplicit, sslContext) {

    @Volatile
    private var resumeSessions = true

    override fun _openDataConnection_(command: String?, arg: String?): Socket? {
        if (!resumeSessions || dataConnectionMode != FTPClient.PASSIVE_LOCAL_DATA_CONNECTION_MODE) {
            return super._openDataConnection_(command, arg)
        }

        val isInet6 = remoteAddress is Inet6Address
        if ((isUseEPSVwithIPv4 || isInet6) && epsv() == FTPReply.ENTERING_EPSV_MODE) {
            _parseExtendedPassiveModeReply(_replyLines[0])
        } else {
            if (isInet6 || pasv() != FTPReply.ENTERING_PASSIVE_MODE) return null
            _parsePassiveModeReply(_replyLines[0])
        }

        val plain = Socket()
        try {
            if (receiveDataSocketBufferSize > 0) plain.receiveBufferSize = receiveDataSocketBufferSize
            if (sendDataSocketBufferSize > 0) plain.sendBufferSize = sendDataSocketBufferSize
            passiveLocalIPAddress?.let { plain.bind(InetSocketAddress(it, 0)) }
            plain.soTimeout = dataTimeoutMs
            plain.connect(InetSocketAddress(passiveHost, passivePort), connectTimeout)

            if (restartOffset > 0 && !restart(restartOffset) || !FTPReply.isPositivePreliminary(sendCommand(command, arg))) {
                plain.close()
                return null
            }

            val secure: SSLSocket
            try {
                secure = sslContext.socketFactory
                    .createSocket(ControlPortSocket(plain, controlPort), controlHost, controlPort, true) as SSLSocket
                secure.useClientMode = true
                enabledProtocols?.let { secure.enabledProtocols = it }
                if (isEndpointCheckingEnabled) {
                    // the control connection sets this; a session can only be resumed by a socket with the same setting
                    val parameters = secure.sslParameters
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    secure.sslParameters = parameters
                }
                secure.startHandshake()
            } catch (e: Exception) {
                // this TLS stack does not cooperate with the wrapped socket: give up resuming, answer the
                // transfer the server is still waiting for, and start over the way the stock client does
                resumeSessions = false
                runCatching { plain.close() }
                getReply()
                return super._openDataConnection_(command, arg)
            }
            return secure
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw e
        }
    }
}
