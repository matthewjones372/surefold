package io.github.matthewjones372.surefold.auth.oidc

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import dev.surefold.clock
import dev.surefold.fail.Either
import io.github.matthewjones372.surefold.auth.OidcError
import io.github.matthewjones372.surefold.auth.OidcIdentity
import io.github.matthewjones372.surefold.auth.OidcSignIn
import io.github.matthewjones372.surefold.auth.OidcStart
import io.github.matthewjones372.surefold.auth.Secret
import io.github.matthewjones372.surefold.auth.constantTimeEquals
import java.io.IOException
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import com.nimbusds.oauth2.sdk.auth.Secret as NimbusSecret

/**
 * [OidcSignIn] on Nimbus: the authorization code flow with PKCE, a client secret sent as HTTP Basic, and an ID token
 * whose signature, issuer, audience, expiry and nonce are each checked here, so each failure has its own [OidcError].
 * Expiry allows [leeway] for clocks that disagree, and reads `clock.get()`.
 */
class NimbusOidc(
    private val provider: OIDCProviderMetadata,
    private val clientId: String,
    private val clientSecret: Secret,
    private val scopes: List<String> = listOf("openid", "email"),
    private val leeway: Duration = 1.minutes,
) : OidcSignIn {
    @Volatile
    private var keys: JWKSet? = null

    override fun start(redirect: URI): OidcStart {
        val state = State()
        val nonce = Nonce()
        val verifier = CodeVerifier()
        val scope = Scope().apply { scopes.forEach(::add) }
        val url = AuthenticationRequest.Builder(ResponseType.CODE, scope, ClientID(clientId), redirect)
            .state(state)
            .nonce(nonce)
            .codeChallenge(verifier, CodeChallengeMethod.S256)
            .endpointURI(provider.authorizationEndpointURI)
            .build()
            .toURI()
        return OidcStart(url, redirect, state.value, Secret(nonce.value), Secret(verifier.value))
    }

    override fun finish(start: OidcStart, code: String, state: String): Either<OidcError, OidcIdentity> {
        if (!constantTimeEquals(state, start.state)) return Either.Left(OidcError.StateMismatch)
        val token = when (val exchanged = exchange(start, code)) {
            is Either.Left -> return exchanged
            is Either.Right -> exchanged.value
        }
        val claims = when (val verified = verified(token)) {
            is Either.Left -> return verified
            is Either.Right -> verified.value
        }
        return try {
            checked(claims, start)
        } catch (unread: java.text.ParseException) {
            Either.Left(OidcError.Malformed("the ID token's claims could not be read: ${unread.message}"))
        }
    }

    private fun exchange(start: OidcStart, code: String): Either<OidcError, SignedJWT> = try {
        val request = TokenRequest.Builder(
            provider.tokenEndpointURI,
            ClientSecretBasic(ClientID(clientId), NimbusSecret(clientSecret.reveal())),
            AuthorizationCodeGrant(AuthorizationCode(code), start.redirect, CodeVerifier(start.verifier.reveal())),
        ).build()
        val response = OIDCTokenResponseParser.parse(request.toHTTPRequest().send())
        when {
            !response.indicatesSuccess() ->
                Either.Left(OidcError.Exchange("${response.toErrorResponse().errorObject}"))

            else -> when (val idToken = (response as OIDCTokenResponse).oidcTokens.idToken) {
                is SignedJWT -> Either.Right(idToken)
                else -> Either.Left(OidcError.Signature("the ID token is not signed"))
            }
        }
    } catch (unreachable: IOException) {
        Either.Left(OidcError.Exchange("the token endpoint could not be reached: ${unreachable.message}"))
    } catch (unread: ParseException) {
        Either.Left(OidcError.Malformed("the token response could not be read: ${unread.message}"))
    }

    private fun verified(token: SignedJWT): Either<OidcError, JWTClaimsSet> = try {
        val verifier = verifier(token)
        when {
            verifier == null ->
                Either.Left(OidcError.Signature("no key ${token.header.keyID} for ${token.header.algorithm}"))

            !token.verify(verifier) -> Either.Left(OidcError.Signature("the signature does not match its key"))

            else -> Either.Right(token.jwtClaimsSet)
        }
    } catch (refused: JOSEException) {
        Either.Left(OidcError.Signature("${refused.message}"))
    } catch (unread: java.text.ParseException) {
        Either.Left(OidcError.Malformed("the ID token's claims could not be read: ${unread.message}"))
    } catch (unreachable: IOException) {
        Either.Left(OidcError.Exchange("the provider's keys could not be fetched: ${unreachable.message}"))
    }

    /** A verifier for the provider's key [token] names, fetching the keys again once if it is new: keys rotate. */
    private fun verifier(token: SignedJWT): JWSVerifier? {
        val kid = token.header.keyID
        val key = keys().getKeyByKeyId(kid) ?: fetchKeys().getKeyByKeyId(kid)
        val algorithm = token.header.algorithm.name
        return when {
            key is RSAKey && (algorithm.startsWith("RS") || algorithm.startsWith("PS")) -> RSASSAVerifier(key)
            key is ECKey && algorithm.startsWith("ES") -> ECDSAVerifier(key)
            else -> null
        }
    }

    private fun keys(): JWKSet = keys ?: fetchKeys()

    private fun fetchKeys(): JWKSet = JWKSet.load(provider.jwkSetURI.toURL()).also { keys = it }

    private fun checked(claims: JWTClaimsSet, start: OidcStart): Either<OidcError, OidcIdentity> {
        val issuer = provider.issuer.value
        val now = clock.get().now()
        val expiry = claims.expirationTime?.toInstant()
        val authorizedParty = claims.getStringClaim("azp")
        return when {
            claims.issuer != issuer -> Either.Left(OidcError.WrongIssuer(issuer, claims.issuer))

            clientId !in claims.audience || (authorizedParty != null && authorizedParty != clientId) ->
                Either.Left(OidcError.WrongAudience(clientId, claims.audience))

            expiry == null || expiry + leeway.toJavaDuration() <= now -> Either.Left(OidcError.Expired(expiry))

            !constantTimeEquals("${claims.getStringClaim("nonce")}", start.nonce.reveal()) ->
                Either.Left(OidcError.NonceMismatch)

            claims.subject == null -> Either.Left(OidcError.Malformed("the ID token names no subject"))

            else -> Either.Right(
                OidcIdentity(
                    claims.subject,
                    issuer,
                    claims.getStringClaim("email").takeIf { claims.getBooleanClaim("email_verified") == true },
                ),
            )
        }
    }

    companion object {
        /** The provider at [issuer], from its `/.well-known/openid-configuration`, which must name the same issuer. */
        fun discover(issuer: URI, clientId: String, clientSecret: Secret): NimbusOidc =
            NimbusOidc(OIDCProviderMetadata.resolve(Issuer(issuer)), clientId, clientSecret)
    }
}
