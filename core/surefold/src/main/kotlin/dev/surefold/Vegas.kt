package dev.surefold

import kotlin.math.log10
import kotlin.math.max

/**
 * TCP Vegas, as a concurrency limit (specs 0171 and 0187). The fastest window seen is the dependency unloaded; a window
 * slower than that means about `limit * (1 - unloaded / took)` calls are queueing inside it. Windows rather than single
 * calls on both sides, since the fastest single call is luck, and comparing with it reads a dependency's ordinary
 * jitter as queueing, more so the higher the limit.
 *
 * The limit moves once per window of about `limit` calls. With no queue it doubles until the first window that queues,
 * as TCP's slow start does, and grows by a tenth after that; with a short one by `log10(limit)`; with a long one it
 * drops at once to what the dependency is serving plus a short queue. What counts as no, short or long queue scales
 * with the limit as well as `log10(limit)`: jitter of a few percent reads as that many percent of the limit queueing,
 * which at a limit of hundreds is past any fixed number of calls. A failed call backs it off at once.
 */
internal class Vegas(initial: Int, private val min: Int, private val max: Int) {
    private var limit = initial.toDouble()
    private var unloaded = Double.MAX_VALUE
    private var fastestThisEpoch = Double.MAX_VALUE
    private var windows = 0L
    private var starting = true

    private var windowCalls = 0
    private var windowTook = 0L
    private var windowMostInside = 0
    private var windowInside = 0L

    /** The limit after a call that took [tookNanos] and found [inFlight] inside when it started, itself among them. */
    fun sample(tookNanos: Long, inFlight: Int, dropped: Boolean): Int {
        if (dropped) {
            limit = max(min.toDouble(), limit * BACKOFF)
            starting = false
            windowCalls = 0
            return limit.toInt()
        }
        windowCalls++
        windowTook += max(1L, tookNanos)
        windowMostInside = maxOf(windowMostInside, inFlight)
        windowInside += inFlight
        if (windowCalls < max(MIN_WINDOW.toDouble(), limit)) return limit.toInt()

        val average = windowTook.toDouble() / windowCalls
        unloaded = minOf(unloaded, average)
        fastestThisEpoch = minOf(fastestThisEpoch, average)
        // What the dependency is serving at once, by Little's law on the calls inside. For a window or two after a
        // cut that is what each call found when it started, behind the higher limit before the cut, and reading
        // those calls against the lower limit cuts again, halving it window after window. Otherwise it is the
        // limit, since a freed permit takes a moment to be taken up, so calls find fewer inside than the limit
        // while that moment is part of how long they take.
        val serving = max(limit, windowInside.toDouble() / windowCalls) * unloaded / average
        val busy = windowMostInside >= limit / 2
        windowCalls = 0
        windowTook = 0
        windowMostInside = 0
        windowInside = 0

        val queued = limit * (1 - unloaded / average)
        val step = max(1.0, log10(limit))
        val calm = queued < max(1.0, limit * CALM)
        if (!calm) starting = false
        val epochEnds = ++windows % FORGET_EVERY == 0L
        val next =
            when {
                // Once an epoch, down to what the dependency is serving, so that every epoch has a window with no queue
                // in it, as BBR probes for its round trip: the fastest window of an epoch that was queueing throughout
                // would otherwise be forgotten into unloaded, and the limit would queue more each epoch.
                epochEnds -> minOf(limit, max(serving, limit * MOST_CUT))

                // Only a limit that is being used grows: an idle one would otherwise climb to max on quiet calls.
                calm && busy -> limit + max(step, limit * if (starting) 1.0 else GROWTH)

                queued <= max(GROW_BELOW * step, limit * SHORT) && busy -> limit + step

                queued >= max(SHRINK_ABOVE * step, limit * LONG) ->
                    minOf(limit, max(serving + GROW_BELOW * step, limit * MOST_CUT))

                else -> limit
            }
        // Forget the fastest window now and then, so a dependency that got slower for good is not read as queueing
        // for ever.
        if (epochEnds) {
            unloaded = fastestThisEpoch
            fastestThisEpoch = Double.MAX_VALUE
        }
        limit = next.coerceIn(min.toDouble(), max.toDouble())
        return limit.toInt()
    }

    private companion object {
        const val BACKOFF = 0.9
        const val GROWTH = 0.1
        const val MOST_CUT = 0.5
        const val GROW_BELOW = 3
        const val SHRINK_ABOVE = 6
        const val CALM = 0.02
        const val SHORT = 0.05
        const val LONG = 0.15
        const val MIN_WINDOW = 16
        const val FORGET_EVERY = 100L
    }
}
