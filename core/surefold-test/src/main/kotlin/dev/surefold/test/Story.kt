package dev.surefold.test

import dev.surefold.SurefoldLocal
import dev.surefold.surefoldLocal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block] as a story named [title], by default the calling test's name, and prints its steps when it ends. A step
 * that throws ends it with a [StoryFailed] whose message is the story up to that step, never coloured.
 *
 * The printed copy is coloured under `-Dsurefold.test.colour=always`, `FORCE_COLOR` or IntelliJ, and not under `never`
 * or `NO_COLOR`. A Gradle test worker does not see the shell's environment; the `dev.surefold.wiring` plugin hands
 * these on to every test task, `-Psurefold.test.colour` included. In a `surefold-junit` suite only the console copy is
 * coloured.
 */
@OptIn(StorySeam::class)
fun story(title: String = storyTitle.get() ?: callingTest(), block: Story.() -> Unit) {
    Story().telling(title, block)
}

/**
 * [story], for checks that are values: a step whose value [failed] finds a failure in is marked failed, and the story
 * goes on.
 */
@StorySeam
fun <A> tellStory(title: String, failed: (Any?) -> Throwable?, block: Story.() -> A): A =
    Story(failed).telling(title, block)

private fun <A> Story.telling(title: String, block: Story.() -> A): A {
    val answer =
        try {
            block()
        } catch (failure: Throwable) {
            tell(title)
            // Led by the failing step's own frame, so the first line a test runner links to is the assertion.
            throw StoryFailed(told(title, colour = false), failure).apply {
                stackTrace = failure.fromTheTest().toTypedArray().ifEmpty { stackTrace }
            }
        }
    tell(title)
    return answer
}

/** A seam for surefold's own modules that run stories, such as a test engine that names and captures each test. */
@RequiresOptIn("A seam for surefold's own modules that run stories.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class StorySeam

/** The title of a story told without one; unbound, the calling test's name. */
@StorySeam val storyTitle: SurefoldLocal<String?> = surefoldLocal { null }

/** Where a story's transcript goes when it ends; unbound, stdout, coloured as [story] says. */
@StorySeam val storyTranscript: SurefoldLocal<((Transcript) -> Unit)?> = surefoldLocal { null }

/**
 * A story's transcript twice: [plain] for anything kept, such as a report, and [coloured] for a console, coloured as
 * [story] says and so the same as [plain] when colour is off (spec 0296).
 */
@StorySeam class Transcript internal constructor(val plain: String, val coloured: String)

@OptIn(StorySeam::class)
private fun Story.tell(title: String) {
    val sink = storyTranscript.get()
    if (sink == null) println(told(title, Colour.wanted)) else sink(transcript(title, Colour.wanted))
}

@OptIn(StorySeam::class)
internal fun Story.transcript(title: String, colour: Boolean): Transcript {
    val plain = told(title, colour = false)
    return Transcript(plain, if (colour) told(title, colour = true) else plain)
}

/** A story that failed: the message is the transcript up to the failing step, and the cause what it threw. */
class StoryFailed(transcript: String, cause: Throwable) : AssertionError(transcript, cause)

// Given, When and Then are capitalised because `when` is a keyword, and the five read as one set.
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
class Story internal constructor(private val failedBy: (Any?) -> Throwable? = { null }) {

    private val steps = mutableListOf<Step>()
    private var depth = 0

    fun <A> Given(text: String, block: () -> A): A = step("Given", text, null, block)

    fun <A> When(text: String, block: () -> A): A = step("When", text, null, block)

    fun <A> Then(text: String, block: () -> A): A = step("Then", text, null, block)

    fun <A> And(text: String, block: () -> A): A = step("And", text, null, block)

    fun <A> But(text: String, block: () -> A): A = step("But", text, null, block)

    fun Given(text: String) = Waiting("Given", text)

    fun When(text: String) = Waiting("When", text)

    fun Then(text: String) = Waiting("Then", text)

    fun And(text: String) = Waiting("And", text)

    fun But(text: String) = Waiting("But", text)

    /** A step that waits for its block to stop throwing. */
    inner class Waiting internal constructor(private val keyword: String, private val text: String) {

        /** [eventually], as a step: its line shows the tries, and a [GaveUp] tells its last failure under it. */
        fun <A> eventually(within: Duration, every: Duration = 20.milliseconds, block: () -> A): A {
            val tries = Tries()
            // Named in full: inside this class, `eventually` is this member, and the step would call itself.
            return step(keyword, text, tries) {
                dev.surefold.test.eventually(within, every) {
                    tries.count++
                    block()
                }
            }
        }
    }

    private fun <A> step(keyword: String, text: String, tries: Tries?, block: () -> A): A {
        val step = Step(keyword, text, depth, tries)
        steps += step
        val started = System.nanoTime()
        depth++
        try {
            return block().also { answer ->
                step.outcome = failedBy(answer)?.let { failed(step, it) } ?: Outcome.Passed
            }
        } catch (failure: Throwable) {
            step.outcome = failed(step, failure)
            throw failure
        } finally {
            depth--
            step.took = (System.nanoTime() - started).nanoseconds
        }
    }

    /** Only the innermost step that saw [failure] tells it; the steps around it are marked failed. */
    private fun failed(step: Step, failure: Throwable): Outcome.Failed =
        Outcome.Failed(
            failure,
            told = steps.none { it !== step && (it.outcome as? Outcome.Failed)?.failure === failure },
        )

    internal fun told(title: String, colour: Boolean): String {
        val ink = Ink(colour)
        val heads = steps.map { "  ".repeat(it.depth + 1) + "${it.mark} ${it.keyword} ${it.text}" }
        val width = heads.maxOfOrNull { it.length } ?: 0
        return buildString {
            append(ink.bold("Story: $title"))
            steps.zip(heads).forEach { (step, head) ->
                val padded = head.padEnd(width + GAP)
                val failed = step.outcome as? Outcome.Failed
                append('\n')
                append(if (failed != null) ink.red(ink.bold(padded)) else padded.replaceFirst("✓", ink.green("✓")))
                append(ink.dim(step.timing()))
                if (failed != null && failed.told) {
                    val indent = "  ".repeat(step.depth + 3)
                    failed.said().lines().forEach { append('\n').append(ink.red(indent + it)) }
                    // A frame printed as a stack trace prints one, which an IDE's console turns into a link.
                    failed.failure.fromTheTest().firstOrNull()?.let { append('\n').append(ink.dim("${indent}at $it")) }
                }
            }
        }
    }

    private class Tries {
        var count = 0
    }

    private class Step(val keyword: String, val text: String, val depth: Int, val tries: Tries?) {
        var outcome: Outcome? = null
        var took: Duration = Duration.ZERO

        val mark: String
            get() = if (outcome is Outcome.Failed) "✗" else "✓"

        fun timing(): String {
            val time =
                if (took < 1.seconds) "${took.inWholeMilliseconds} ms"
                else "%.1f s".format(took.inWholeMilliseconds / MILLIS)
            val count = tries?.count ?: 0
            return if (count > 1) "$time, $count tries" else time
        }
    }

    private sealed interface Outcome {
        data object Passed : Outcome

        class Failed(val failure: Throwable, val told: Boolean) : Outcome {
            /** What went wrong, in its own words: a [GaveUp] tells its last failure, not that it gave up. */
            fun said(): String {
                val what = if (failure is GaveUp) failure.cause ?: failure else failure
                return what.message ?: what.toString()
            }
        }
    }

    private companion object {
        const val GAP = 4
        const val MILLIS = 1000.0
    }
}

/** The first frame outside this file, which for a JUnit test is the test method and its backticked name. */
private fun callingTest(): String =
    StackWalker.getInstance().walk { frames ->
        frames
            .filter { it.className != "dev.surefold.test.StoryKt" }
            .findFirst()
            .map { it.methodName }
            .orElse("a story")
    }

/**
 * Where [this] was thrown in the test's own code: its frames from the first that is not the story's or `eventually`'s,
 * surefold's core, an assertion library's, Kotlin's, Arrow's or the JDK's. A [GaveUp] answers for its last failure.
 */
internal fun Throwable.fromTheTest(): List<StackTraceElement> {
    val thrown = if (this is GaveUp) cause ?: this else this
    return thrown.stackTrace.dropWhile { frame ->
        frame.className.substringBefore('$') in ours ||
            frame.className.substringBeforeLast('.') == SUREFOLD ||
            libraries.any { frame.className.startsWith(it) }
    }
}

private const val SUREFOLD = "dev.surefold"

// Not every class under surefold's prefix: surefold-test's own tests live there, and must point at themselves.
private val ours = setOf("$SUREFOLD.test.StoryKt", "$SUREFOLD.test.Story", "$SUREFOLD.test.EventuallyKt")

private val libraries = listOf("$SUREFOLD.assert.", "org.opentest4j.", "kotlin.", "java.", "jdk.", "sun.", "arrow.")
