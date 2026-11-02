package io.github.matthewjones372.surefold.auth.oidc

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.auth.OidcError
import io.github.matthewjones372.surefold.auth.OidcIdentity
import io.github.matthewjones372.surefold.auth.Secret
import java.net.URI
import java.time.Instant
import java.util.Date

/** The code flow against a provider stubbed with its own keys: each check fails on its own (spec 0228). */
object NimbusOidcSuite : Suite() {
    private val redirect = URI("https://bookings.example/auth/callback")

    init {
        test("a good callback answers the identity, with the email the provider verified") {
            StubProvider().use { provider ->
                provider.signIn() shouldBe
                    Either.Right(OidcIdentity("user-7", provider.issuer.toString(), "ada@example.com"))
            }
        }

        test("an email the provider has not verified is left out") {
            StubProvider().use { provider ->
                provider.signIn { nonce -> good(nonce).claim("email_verified", false).build() } shouldBe
                    Either.Right(OidcIdentity("user-7", provider.issuer.toString(), null))
            }
        }

        test("a callback with another state is refused before the code is spent") {
            StubProvider().use { provider ->
                val start = provider.oidc().start(redirect)
                provider.authorize(start.url, "c1")
                provider.oidc().finish(start, "c1", "forged") shouldBe Either.Left(OidcError.StateMismatch)
                (provider.oidc().finish(start, "c1", start.state) is Either.Right) shouldBe true
            }
        }

        test("a code the provider refuses, or one sent with the wrong PKCE verifier, is an exchange error") {
            StubProvider().use { provider ->
                val start = provider.oidc().start(redirect)
                provider.oidc().finish(start, "never-issued", start.state) shouldBe
                    Either.Left(OidcError.Exchange("invalid_grant"))
                provider.authorize(start.url, "c2")
                val stolen = start.copy(verifier = Secret("a".repeat(43)))
                provider.oidc().finish(stolen, "c2", start.state) shouldBe
                    Either.Left(OidcError.Exchange("invalid_grant"))
            }
        }

        test("a token with another nonce, audience, issuer or expiry each fail their own check") {
            StubProvider().use { provider ->
                provider.signIn { good("replayed").build() } shouldBe Either.Left(OidcError.NonceMismatch)
                provider.signIn { nonce -> good(nonce).audience("someone-else").build() } shouldBe
                    Either.Left(OidcError.WrongAudience(StubProvider.CLIENT, listOf("someone-else")))
                provider.signIn { nonce -> good(nonce).issuer("https://evil.example").build() } shouldBe
                    Either.Left(OidcError.WrongIssuer(provider.issuer.toString(), "https://evil.example"))
                val past = Instant.now().minusSeconds(300).epochSecond
                provider.signIn { nonce -> good(nonce).expirationTime(Date(past * 1000)).build() } shouldBe
                    Either.Left(OidcError.Expired(Instant.ofEpochSecond(past)))
            }
        }

        test("a token signed by another key under the provider's key id fails its signature") {
            StubProvider().use { provider ->
                provider.signer = RSAKeyGenerator(2048).keyID("k1").generate()
                (provider.signIn().leftOrNull() is OidcError.Signature) shouldBe true
            }
        }
    }

    private fun StubProvider.oidc() =
        NimbusOidc.discover(issuer, StubProvider.CLIENT, Secret(StubProvider.CLIENT_SECRET))

    private fun StubProvider.signIn(
        made: StubProvider.(String) -> JWTClaimsSet = { nonce -> good(nonce).build() },
    ): Either<OidcError, OidcIdentity> {
        claims = { nonce -> made(this, nonce) }
        val start = oidc().start(redirect)
        authorize(start.url, "code")
        return oidc().finish(start, "code", start.state)
    }
}
