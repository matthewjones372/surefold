package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import kotlin.time.Duration.Companion.minutes

private val text = object : EventCodec<String> {
    override fun encode(event: String): ByteArray = event.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private val diaryOf = PersistenceId("diary", "sam")

private sealed interface Diary

private data class Note(val text: String) : Diary

private data class Written(val reply: Reply<List<String>>) : Diary

/** Writes each note straight to its scope's journal, and reads back what is there. */
private fun diary() = behaviour<Diary, Long>(0L) { ctx, last, message ->
    when (message) {
        is Note -> {
            val next = ctx.journal.append(diaryOf, last, listOf(text.encode(message.text))).getOrNull() ?: last
            become(next)
        }

        is Written -> {
            message.reply(ctx.journal.events(diaryOf, text))
            stay()
        }
    }
}

object JournalSuite : Suite() {
    init {
        test("a test's actors write to the test's journal, which the test can read") {
            testActors {
                val diary = spawn("diary", diary())

                diary.send(Note("one"))
                diary.send(Note("two"))

                journal.events(diaryOf, text) shouldContainExactly listOf("one", "two")
            }
        }

        test("on threads, an actor writes to its scope's journal") {
            val journal = InMemoryJournal()
            val written = scope {
                journal(journal)
                val diary = spawn("diary", diary())
                diary.tell(Note("one"))
                diary.tell(Note("two"))
                diary.ask(1.minutes) { Written(it) }
            }

            written shouldBeRight listOf("one", "two")
        }

        test("on threads, a scope with no journal says so") {
            shouldThrow<IllegalStateException>({ scope { journal() } }) { failed ->
                failed.message shouldBe "no journal: give the scope one with journal(…)"
            }
        }
    }
}
