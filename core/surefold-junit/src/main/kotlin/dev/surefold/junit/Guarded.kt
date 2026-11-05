package dev.surefold.junit

import dev.surefold.ExitCase
import dev.surefold.Resource
import dev.surefold.ResourceScope
import dev.surefold.resource
import dev.surefold.resourceScope
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One [R] for a whole run of the `surefold` engine, lent to at most [permits] tests at once (spec 0253). Declared as an
 * `object`, it is acquired the first time a test uses it and released after the run's last suite. A test that [lease]s
 * it already holds a permit, so it must not also call [use]: with one permit left, it would wait for itself.
 */
abstract class Guarded<R>(val permits: Int) {
    init {
        require(permits >= 1) { "${javaClass.simpleName} has $permits permits; a guard needs at least one" }
    }

    /** What the run acquires on first use and releases after its last suite. */
    abstract val resource: Resource<R>

    private val lent = Semaphore(permits, true)

    /** A suite's [SharedSuite.shared]: each of its tests holds a permit for its whole body, passed or failed. */
    fun lease(): Resource<R> = resource { GuardedRun.current(this@Guarded).leased(this@Guarded) }

    /** Runs [block] with the resource, holding a permit while it runs. */
    fun <B> use(block: (R) -> B): B = withPermit { block(GuardedRun.current(this).valueOf(this)) }

    internal fun <B> withPermit(block: () -> B): B {
        lent.acquire()
        try {
            return block()
        } finally {
            lent.release()
        }
    }

    internal fun acquireIn(scope: ResourceScope): R = resource.invoke(scope)
}

/** The guards one engine run has acquired, released when it closes, and those the suite being acquired leased. */
internal class GuardedRun private constructor() : ResourceScope {
    private val lock = ReentrantLock()
    private val values = HashMap<Guarded<*>, Any?>()
    private val releases = ArrayList<(ExitCase) -> Unit>()
    private val leasing = ThreadLocal<MutableSet<Guarded<*>>?>()

    override fun onRelease(release: (ExitCase) -> Unit) {
        lock.withLock { releases += release }
    }

    /** The guard's value, acquired once for the run: the lock is held while acquiring, so two first uses wait. */
    @Suppress("UNCHECKED_CAST") // each value is put under its own guard, so it is that guard's R
    fun <R> valueOf(guard: Guarded<R>): R = lock.withLock {
        if (guard in values) values[guard] as R else guard.acquireIn(this).also { values[guard] = it }
    }

    fun <R> leased(guard: Guarded<R>): R {
        leasing.get()?.add(guard)
        return valueOf(guard)
    }

    /** What [acquire] answers, and the guards it leased, by class name, so every suite takes permits in one order. */
    fun <A> leasesOf(acquire: () -> A): Pair<A, List<Guarded<*>>> {
        val leased = HashSet<Guarded<*>>()
        leasing.set(leased)
        try {
            return acquire() to leased.sortedBy { it.javaClass.name }
        } finally {
            leasing.remove()
        }
    }

    companion object {
        @Volatile private var open: GuardedRun? = null

        fun current(guard: Guarded<*>): GuardedRun =
            checkNotNull(open) {
                "${guard.javaClass.simpleName} was used outside a run of the surefold engine, which acquires and " +
                    "releases it"
            }

        /** Runs [block] with a run open, then releases what it acquired, as a resource scope releases. */
        fun opened(block: (GuardedRun) -> Unit) = resourceScope {
            val outer = open
            val run = GuardedRun().also { open = it }
            onRelease { open = outer }
            // Last in, so first out: the guards are released before the outer run is put back.
            onRelease { resourceScope { run.lock.withLock { run.releases.toList() }.forEach { onRelease(it) } } }
            block(run)
        }
    }
}
