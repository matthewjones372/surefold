package dev.surefold

/** Marks the seam a replaying test binds (spec 0042), which nothing outside surefold's own modules should implement. */
@RequiresOptIn("A seam for surefold-replay, which decides which fork goes next.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TurnSeam

/**
 * Who goes next, told at every place a surefold thread waits, wakes another, or starts or ends a fork (spec 0042).
 * Bound with [takingTurns], it lets one thread at a time run between those places, and chooses which. Each call is made
 * on the thread it names, while that thread holds the turn, except [started] and [woke], which wait for it.
 */
@TurnSeam
interface Turns {

    /** A fork is about to be handed to its executor: what its body will start with, to say which fork it is. */
    fun forking(): Any

    /** The body of the fork [ticket] names starts on this thread, and waits here for its turn. */
    fun started(ticket: Any)

    /** This thread's fork body has ended: the turn goes on, and this thread takes no more. */
    fun ended()

    /**
     * This thread is about to park, and the turn goes on: whether it should. A wake that came first answers false once
     * this thread has the turn again, and the park returns at once, as a spurious wake, for the caller to look again:
     * the JDK's own permit for that wake may already have been spent by a wait nothing here sees.
     */
    fun parking(): Boolean

    /** This thread is back from a park, and waits here for its turn. */
    fun woke()

    /** [thread] is being unparked or interrupted, so may run again. */
    fun waking(thread: Thread)
}

/** Runs [block] with [turns] deciding the order of every fork it opens, and theirs. */
@TurnSeam fun <A> takingTurns(turns: Turns, block: () -> A): A = turnsBound.locally(turns, block)

@OptIn(TurnSeam::class) internal val turnsBound: SurefoldLocal<Turns?> = surefoldLocal { null }
