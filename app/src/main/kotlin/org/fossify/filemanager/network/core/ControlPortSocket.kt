package org.fossify.filemanager.network.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketOption
import java.nio.channels.SocketChannel

/**
 * Transparent wrapper around a connected socket that reports [reportedPort] as its remote port.
 *
 * TLS stacks (the JDK's and Conscrypt, i.e. Android's) key their client session cache on the remote host and the
 * port *of the socket underneath* a layered SSL socket. Reporting the FTP control port for data connections lets
 * the data connection find and resume the control connection's TLS session, which many FTPS servers require.
 */
internal class ControlPortSocket(
    private val delegate: Socket,
    private val reportedPort: Int,
) : Socket() {
    override fun connect(endpoint: SocketAddress?) = delegate.connect(endpoint)

    override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)

    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)

    override fun getInetAddress(): InetAddress? = delegate.inetAddress

    override fun getLocalAddress(): InetAddress? = delegate.localAddress

    override fun getPort(): Int = reportedPort

    override fun getLocalPort(): Int = delegate.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

    override fun getChannel(): SocketChannel? = delegate.channel

    override fun getInputStream(): InputStream = delegate.getInputStream()

    override fun getOutputStream(): OutputStream = delegate.getOutputStream()

    override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)

    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)

    override fun getSoLinger(): Int = delegate.soLinger

    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)

    override fun setOOBInline(on: Boolean) = delegate.setOOBInline(on)

    override fun getOOBInline(): Boolean = delegate.oobInline

    override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)

    override fun getSoTimeout(): Int = delegate.soTimeout

    override fun setSendBufferSize(size: Int) = delegate.setSendBufferSize(size)

    override fun getSendBufferSize(): Int = delegate.sendBufferSize

    override fun setReceiveBufferSize(size: Int) = delegate.setReceiveBufferSize(size)

    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize

    override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)

    override fun getKeepAlive(): Boolean = delegate.keepAlive

    override fun setTrafficClass(tc: Int) = delegate.setTrafficClass(tc)

    override fun getTrafficClass(): Int = delegate.trafficClass

    override fun setReuseAddress(on: Boolean) = delegate.setReuseAddress(on)

    override fun getReuseAddress(): Boolean = delegate.reuseAddress

    override fun close() = delegate.close()

    override fun shutdownInput() = delegate.shutdownInput()

    override fun shutdownOutput() = delegate.shutdownOutput()

    override fun isConnected(): Boolean = delegate.isConnected

    override fun isBound(): Boolean = delegate.isBound

    override fun isClosed(): Boolean = delegate.isClosed

    override fun isInputShutdown(): Boolean = delegate.isInputShutdown

    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
        delegate.setPerformancePreferences(connectionTime, latency, bandwidth)

    override fun <T : Any?> setOption(name: SocketOption<T>?, value: T): Socket {
        delegate.setOption(name, value)
        return this
    }

    override fun <T : Any?> getOption(name: SocketOption<T>?): T = delegate.getOption(name)

    override fun supportedOptions(): MutableSet<SocketOption<*>> = delegate.supportedOptions()

    override fun toString(): String = delegate.toString()
}
