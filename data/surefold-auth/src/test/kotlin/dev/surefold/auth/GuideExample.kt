package dev.surefold.auth

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
