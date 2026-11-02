package dev.surefold.auth

import dev.surefold.fail.Either
import java.net.URI
import java.time.Instant

/**
 * Sign-in with an OpenID Connect provider: [start] makes the URL to send the user to, and [finish] checks what the
 * provider sends back to the callback. The redirect and the callback endpoint are the web layer's; this builds the one
 * and checks the other. `surefold-auth-oidc` implements it on Nimbus's OpenID Connect SDK.
 */
interface OidcSignIn {
    fun start(redirect: URI): OidcStart

    /** The identity [start]'s sign-in proved, given the [code] and [state] the provider sent to the callback. */
    fun finish(start: OidcStart, code: String, state: String): Either<OidcError, OidcIdentity>
}

/**
 * A sign-in begun: send the user to [url], and keep the rest, server-side or in a sealed cookie, until the callback.
 * The [verifier] is PKCE's, and with the [nonce] it must not reach anyone but the provider.
 */
data class OidcStart(val url: URI, val redirect: URI, val state: String, val nonce: Secret, val verifier: Secret)

/** Who signed in: [subject] at [issuer], and their [email] only when the provider says it is verified. */
data class OidcIdentity(val subject: String, val issuer: String, val email: String?)

/** Which check a callback failed. */
sealed interface OidcError {
    /** The callback's state is not the one this sign-in started with: a forged or crossed callback. */
    data object StateMismatch : OidcError

    /** The provider would not exchange the code, or could not be reached. */
    data class Exchange(val reason: String) : OidcError

    /** The ID token is not signed by any of the provider's keys. */
    data class Signature(val reason: String) : OidcError

    data class WrongIssuer(val expected: String, val actual: String?) : OidcError

    /** The ID token was issued to another client. */
    data class WrongAudience(val expected: String, val actual: List<String>) : OidcError

    data class Expired(val at: Instant?) : OidcError

    /** The ID token's nonce is not this sign-in's: a token replayed from another. */
    data object NonceMismatch : OidcError

    /** What came back was not an OpenID Connect answer. */
    data class Malformed(val reason: String) : OidcError
}
