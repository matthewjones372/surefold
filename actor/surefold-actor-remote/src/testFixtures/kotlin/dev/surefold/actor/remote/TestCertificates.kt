package dev.surefold.actor.remote

import java.security.KeyStore

/**
 * The certificates surefold's TLS tests use, made by `tls/make.sh` beside them: nodes `n1`, `n2` and `n3` signed by
 * `cluster-ca`, and `n4` by `other-ca`, each naming itself as a DNS subject alternative name.
 */
object TestCertificates {
    val password: CharArray get() = "surefold-test".toCharArray()

    /** The PKCS12 store [name]: a node's key and chain, or `trusts-<ca>` for a CA's certificate alone. */
    fun store(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        checkNotNull(TestCertificates::class.java.getResourceAsStream("/tls/$name.p12")) { "no test store $name" }
            .use { load(it, password) }
    }

    /** [name]'s key and certificate, trusting only what [ca] signed. */
    fun tls(name: String, ca: String = "cluster-ca"): Tls = Tls.mutual(store(name), password, store("trusts-$ca"))
}
