package dev.surefold.junit

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.clock
import dev.surefold.logger
import dev.surefold.test.Story
import dev.surefold.test.StorySeam
import dev.surefold.test.storyTitle
import dev.surefold.test.storyTranscript
import dev.surefold.test.tellStory

/**
 * surefold-test's `story`, as a suite's test body, titled by default with the test's name. The checks its steps make
 * are recorded as the test's; a step whose checks failed is marked failed and the story goes on, and a step that
 * throws still ends it.
 */
@OptIn(StorySeam::class)
fun story(title: String = storyTitle.get() ?: "a story", block: Story.() -> Unit) {
    val into = record.get()
    var seen = into?.size ?: 0
    val failedStep: (Any?) -> Throwable? = { _ ->
        into?.let { it.since(seen).also { _ -> seen = it.size }.failure }
    }
    tellStory(title, failedStep, block)
}

/** Runs [block] with its stories titled [test] and their transcripts logged, so they are reported with its logs. */
@OptIn(StorySeam::class)
internal fun <A> tellingStories(test: String, block: () -> A): A = storyTitle.locally(test) {
    storyTranscript.locally({ logger.get().log(LogLine(LogLevel.Info, it, clock.get().now())) }, block)
}
