package dev.surefold.auth.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Date

/**
 * An OpenID Connect provider for tests, on the JDK's HTTP server: discovery, its keys, and a token endpoint that
 * checks the client and PKCE before answering an ID token signed with its own key. [claims] decides what the token
 * says, and [signer] which key signs it, so a test can make it lie in one way at a time.
 */
class StubProvider : AutoCloseable {
    val key: RSAKey = RSAKeyGenerator(KEY_BITS).keyID("k1").generate()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val issuer: URI = URI("http://127.0.0.1:${server.address.port}")

    @Volatile
    var signer: RSAKey = key

    @Volatile
    var claims: (nonce: String) -> JWTClaimsSet = { nonce -> good(nonce).build() }

    /** What the authorization endpoint would have kept for a code: the PKCE challenge and the nonce. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    init {
        server.createContext("/.well-known/openid-configuration") { it.json(discovery()) }
        server.createContext("/jwks") { it.json(JWKSet(key.toPublicJWK()).toString()) }
        server.createContext("/token") { it.token() }
        server.start()
    }

    /** Claims that pass every check, as a builder a test can spoil. */
    fun good(nonce: String): JWTClaimsSet.Builder = JWTClaimsSet.Builder()
        .issuer(issuer.toString())
        .subject("user-7")
        .audience(CLIENT)
        .expirationTime(Date.from(java.time.Instant.now().plusSeconds(TTL_SECONDS)))
        .issueTime(Date())
        .claim("nonce", nonce)
        .claim("email", "ada@example.com")
        .claim("email_verified", true)

    /** The user signed in at [url] and was sent back with this code. */
    fun authorize(url: URI, code: String) {
        val query = parse(url.rawQuery)
        pending[code] = query.getValue("code_challenge") to query.getValue("nonce")
    }

    private fun HttpExchange.token() {
        val form = parse(requestBody.readAllBytes().decodeToString())
        val basic = Base64.getEncoder().encodeToString("$CLIENT:$CLIENT_SECRET".toByteArray())
        val (challenge, nonce) = form["code"]?.let(pending::remove) ?: return error("invalid_grant")
        val verified = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(form.getValue("code_verifier").toByteArray()),
        )
        if (requestHeaders.getFirst("Authorization") != "Basic $basic") return error("invalid_client", 401)
        if (verified != challenge) return error("invalid_grant")
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims(nonce))
        token.sign(RSASSASigner(signer))
        json("""{"access_token":"at","token_type":"Bearer","expires_in":3600,"id_token":"${token.serialize()}"}""")
    }

    private fun HttpExchange.error(code: String, status: Int = 400) = json("""{"error":"$code"}""", status)

    private fun HttpExchange.json(body: String, status: Int = 200) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun discovery() = """
        {"issuer":"$issuer","authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token",
         "jwks_uri":"$issuer/jwks","response_types_supported":["code"],"subject_types_supported":["public"],
         "id_token_signing_alg_values_supported":["RS256"]}
    """.trimIndent()

    private fun parse(query: String) = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
        URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8) to
            URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8)
    }

    override fun close() = server.stop(0)

    companion object {
        const val CLIENT = "bookings"
        const val CLIENT_SECRET = "s3cret"
        private const val KEY_BITS = 2048
        private const val TTL_SECONDS = 300L
    }
}
