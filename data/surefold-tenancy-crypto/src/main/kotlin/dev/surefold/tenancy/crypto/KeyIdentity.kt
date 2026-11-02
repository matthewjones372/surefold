package dev.surefold.tenancy.crypto

/** The exact wrapped row whose retained material a view owns; revision bytes never escape. */
class KeyIdentity(val owner: KeyOwner, val purpose: KeyPurpose, val version: Int, revision: ByteArray) {
    private val revision = revision.copyOf()

    init {
        require(version > 0 && revision.isNotEmpty()) { "invalid key identity" }
    }

    override fun equals(other: Any?): Boolean = other is KeyIdentity && owner == other.owner &&
        purpose == other.purpose && version == other.version && revision.contentEquals(other.revision)

    override fun hashCode(): Int = 31 * (31 * (31 * owner.hashCode() + purpose.hashCode()) + version) +
        revision.contentHashCode()

    override fun toString(): String = "KeyIdentity"
}
