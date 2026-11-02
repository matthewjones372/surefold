package dev.surefold.actor.remote

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

private const val TLS_1_3 = "TLSv1.3"

/** The type of a subject alternative name that is a DNS name, as X.509 numbers them. */
private const val DNS_NAME = 2

/**
 * How a node's connections are secured (spec 0073): TLS 1.3 both ways, each side presenting the certificate in
 * [context]'s keys and requiring one its trust store signed. A peer with no certificate, or one signed by another,
 * is refused before a frame crosses. Each connection is made from [context] afresh, so a context whose keys the
 * service rotates serves the new certificate from the next connection on.
 */
class Tls(val context: SSLContext) {

    internal fun listening(): ServerSocket =
        (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = arrayOf(TLS_1_3)
            needClientAuth = true
        }

    /** [connected], to [peer], as the client side of a TLS connection whose handshake is done. */
    internal fun over(connected: Socket, peer: Node): SSLSocket =
        (context.socketFactory.createSocket(connected, peer.host, peer.port, true) as SSLSocket).apply {
            enabledProtocols = arrayOf(TLS_1_3)
            useClientMode = true
            startHandshake()
        }

    /**
     * Throws unless [socket]'s peer certificate names [claimed] as a DNS subject alternative name: a node's identity
     * is its certificate, and the name it gives in its hello must be that one.
     */
    internal fun check(socket: Socket, claimed: Node) {
        val certificate = (socket as SSLSocket).session.peerCertificates.first() as X509Certificate
        val named = certificate.subjectAlternativeNames.orEmpty()
            .filter { it[0] == DNS_NAME }
            .map { it[1] as String }
        if (claimed.name !in named) throw IOException("$claimed gave a certificate for $named")
    }

    companion object {
        /**
         * TLS from key stores: [keys] holds this node's private key and its certificate chain under [password],
         * and [trusted] the certificates of the CAs whose nodes it lets in, usually the cluster's one.
         */
        fun mutual(keys: KeyStore, password: CharArray, trusted: KeyStore): Tls {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keys, password) }.keyManagers
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trusted) }.trustManagers
            return Tls(SSLContext.getInstance(TLS_1_3).apply { init(keyManagers, trustManagers, null) })
        }
    }
}
