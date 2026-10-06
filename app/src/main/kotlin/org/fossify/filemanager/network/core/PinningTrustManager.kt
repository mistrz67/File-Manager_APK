package org.fossify.filemanager.network.core

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS trust decision for FTPS. A certificate is accepted when its fingerprint equals the pinned identity the
 * user trusted before; otherwise it must validate against the system trust store (including the host name).
 * Anything else raises [UntrustedServerException] carrying the presented fingerprint, so the UI can offer to
 * trust it (trust on first use) or warn about a changed certificate.
 */
class PinningTrustManager(
    private val host: String,
    private val pinned: PinnedIdentity?,
) : X509ExtendedTrustManager() {
    private val delegate: X509ExtendedTrustManager? = systemTrustManager()

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
        verify(chain) { delegate?.checkServerTrusted(chain, authType, socket) ?: throw CertificateException("No system trust manager") }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
        verify(chain) { delegate?.checkServerTrusted(chain, authType, engine) ?: throw CertificateException("No system trust manager") }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        verify(chain) { delegate?.checkServerTrusted(chain, authType) ?: throw CertificateException("No system trust manager") }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        throw CertificateException("Client certificates are not supported")

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        throw CertificateException("Client certificates are not supported")

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate?.acceptedIssuers ?: emptyArray()

    private fun verify(chain: Array<out X509Certificate>, systemCheck: () -> Unit) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val presented = PinnedIdentity(PinnedIdentity.TYPE_TLS, PinnedIdentity.fingerprintOf(leaf.encoded))
        if (pinned != null && pinned.fingerprint == presented.fingerprint) return

        if (pinned == null) {
            try {
                systemCheck()
                return
            } catch (ignored: CertificateException) {
                // fall through: not signed by a trusted CA or the host name does not match
            }
        }

        throw UntrustedServerCertificateException(UntrustedServerException(host, presented.serialize(), pinned?.serialize()))
    }

    /** Carries [UntrustedServerException] through the JSSE handshake, which only allows [CertificateException]. */
    class UntrustedServerCertificateException(val untrusted: UntrustedServerException) :
        CertificateException(untrusted.message)

    private companion object {
        fun systemTrustManager(): X509ExtendedTrustManager? {
            return try {
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                factory.init(null as KeyStore?)
                factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull()
                    ?: factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()?.let { WrappedTrustManager(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    private class WrappedTrustManager(private val inner: X509TrustManager) : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            inner.checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            inner.checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            inner.checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            inner.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            inner.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            inner.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = inner.acceptedIssuers
    }
}
