# Sign-in and sessions

`surefold-auth` keeps what signing in rests on, in the Postgres the service already runs: password hashes, sessions
behind opaque tokens, single-use magic links, TOTP second factors, and roles per tenant. `surefold-auth-oidc` adds
sign-in with an OpenID Connect provider. Neither knows about HTTP: cookies, CSRF, redirects and the sign-in pages are
the web layer's.

```kotlin
dependencies {
    implementation("dev.surefold:surefold-auth:0.17.0")
    implementation("dev.surefold:surefold-auth-oidc:0.17.0") // only to sign in with another provider
}
```

There is no user table: a user is a `UserId` the service owns, and the service keeps its users, their emails and
their password hashes where it likes. A sign-in with a password and an optional second factor looks like this:

<!-- auth -->
```kotlin
/** Where the service keeps its users, by email: surefold-auth keeps no user table. */
interface Users {
    fun find(email: String): Pair<UserId, PasswordHash>?

    fun store(user: UserId, hash: PasswordHash)
}

sealed interface SignedIn {
    /** Signed in: [token] goes in the session cookie, and is not seen again. */
    data class Session(val token: Secret) : SignedIn

    /** The password was right, and this user has a second factor: ask for its code. */
    data object CodeNeeded : SignedIn

    data object Refused : SignedIn
}

class Accounts(
    private val users: Users,
    private val passwords: Passwords,
    private val totp: PostgresTotp,
    private val sessions: Sessions,
) {
    fun signIn(email: String, password: Secret, code: String?, tenant: String): SignedIn {
        // An unknown email costs a hash too, so how long a refusal takes says nothing of who has an account.
        val (user, stored) = users.find(email) ?: return SignedIn.Refused.also { passwords.hash(password) }
        when (val checked = passwords.verify(password, stored)) {
            Checked.Invalid -> return SignedIn.Refused
            is Checked.Valid -> checked.rehashed?.let { users.store(user, it) }
        }
        if (totp.enrolled(user)) {
            if (code == null) return SignedIn.CodeNeeded
            if (!totp.verify(user, code)) return SignedIn.Refused
        }
        return SignedIn.Session(sessions.create(user, tenant).token)
    }
}
```

## Tables

The tables ship as a Liquibase changelog in formatted SQL. Include it from the service's own; nothing creates them at
start.

```xml
<include file="surefold/auth/postgres.sql"/>
```

On Flyway, `flyway(SurefoldSchema.AUTH, …)` runs the same SQL ([Flyway](flyway.md)).

| Table | Holds |
|-------|-------|
| `surefold_sessions` | one row per session, by the SHA-256 of its token |
| `surefold_magic_links` | links issued and not yet redeemed, by the SHA-256 of their token |
| `surefold_role_grants`, `surefold_role_permissions` | who holds which role in which tenant, and what each role allows |
| `surefold_totp` | one second factor per user, encrypted |

No token is stored: a copy of the database signs no one in. Time is `clock.get()` throughout, so a `TestClock` ages
sessions, links and codes in a service's own tests.

## Passwords

`Passwords` hashes with Argon2id, from Bouncy Castle, so no native library comes with it. A hash is a PHC string,
`$argon2id$v=19$m=19456,t=2,p=1$salt$hash`, that says its own cost. The defaults are OWASP's: read
`Argon2Settings` from configuration and raise it as hardware allows. A hash made at the old cost still verifies, and
`Checked.Valid.rehashed` carries the hash at the new cost to store in its place.

A password is a `Secret`, whose `toString` is `Secret(****)`, so a log line that includes one says nothing. Hashes are
compared in constant time.

## Sessions

```kotlin
val sessions = PostgresSessions(dataSource, SessionSettings(idle = 30.minutes, absolute = 12.hours), roles)
val (token, session) = sessions.create(UserId("u-1"), tenant = "acme")
sessions.resolve(token)       // Principal(user, tenant, roles, permissions), or null
sessions.rotate(token)        // a new token for the same session; the old one stops resolving
sessions.revoke(token)
sessions.revokeAll(UserId("u-1"))
```

A token is 32 random bytes as URL-safe base64, handed out once. A session ends at whichever comes first: `idle` with no
resolve, or `absolute` since it was created. A resolve moves the idle expiry on, but only once `slide` (a minute) has
passed since the last that did, so a page of twenty requests writes once. Every resolve reads the table, so a rotation
or a revocation on one instance holds on every other at once. Rotate after sign-in and after a privilege change, so a
token fixed before either is no good after. `prune()` deletes expired rows; run it from a recurring job.

## Magic links

```kotlin
val links = PostgresMagicLinks(dataSource, ttl = 15.minutes)
val token = links.issue(UserId("u-1"))   // put it in a URL and email it
links.redeem(token)                      // the user, once; null after, or once expired
```

Redeeming deletes the row in the statement that reads it, so of any number of requests redeeming one link at once,
one gets it. The service sends the email; this module sends nothing.

## Roles

```kotlin
val roles = PostgresRoles(dataSource)
roles.grant(UserId("u-1"), tenant = "acme", role = "venue-staff")
roles.grant(UserId("u-2"), tenant = null, role = "support")    // in every tenant
roles.allow("venue-staff", "bookings:refund")
```

Give `roles` to `PostgresSessions` and each resolved `Principal` carries the roles its user holds in the session's
tenant, and the permissions those allow: `principal.can("bookings:refund")`. A service that keeps roles elsewhere
passes its own `RoleSource` instead.

## Second factors

```kotlin
val totp = PostgresTotp(dataSource, AesKey(Secret(keyFromConfig)))   // base64 of 16, 24 or 32 bytes
val enrolment = totp.enrol(UserId("u-1"), issuer = "Bookings")      // show enrolment.uri as a QR code
totp.confirm(UserId("u-1"), code)                                   // the first code switches it on
totp.verify(UserId("u-1"), code)
```

Codes are RFC 6238's: HMAC-SHA1, 30-second steps, six digits, good within one step either side of now. A code is good
once: the step it matched is stored, and that step and every earlier one are refused after, even to two requests at
once. Secrets are encrypted with AES-GCM under the key from configuration and bound to their user, so a dump of the
database gives no one a second factor. There are no recovery codes: a user who loses a device is re-enrolled.

## Signing in elsewhere

`surefold-auth-oidc` signs in with an OpenID Connect provider, on Nimbus's SDK:

```kotlin
val google: OidcSignIn = NimbusOidc.discover(URI("https://accounts.google.com"), clientId, Secret(clientSecret))
val start = google.start(redirect = URI("https://bookings.example/auth/callback"))
// send the user to start.url; keep start, server-side or in a sealed cookie, until the callback
when (val identity = google.finish(start, code, returnedState)) {
    is Either.Right -> signInAs(identity.value.issuer, identity.value.subject)
    is Either.Left -> refuse(identity.value)    // which check failed: state, nonce, audience, issuer, expiry...
}
```

`finish` checks the state before the code is spent, swaps the code with PKCE, and checks the ID token's signature
against the provider's keys, its issuer, audience, expiry and nonce, each with its own `OidcError`. The identity is the
provider's subject and issuer; key the service's user on both, not on the email, which `OidcIdentity` carries only
when the provider says it is verified.

## Over HTTP

Reading the cookie, CSRF and per-endpoint permission checks belong to the HTTP layer. A service calls
`Sessions.resolve` from its handlers; Surefold ships no adapter to any HTTP framework, and a framework built on
Surefold, such as Pelican, puts its own interface over `Sessions`.
