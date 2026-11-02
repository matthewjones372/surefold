package dev.surefold.junit.samples

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What [MeetingSuite] and [OtherMeetingSuite] wait on; open until a test arms it, so other runs do not wait. */
object Meeting {
    @Volatile var both = CountDownLatch(0)
}

/** Passes only once [OtherMeetingSuite]'s test has started too, so only when the two suites run at once. */
object MeetingSuite : Suite() {
    init {
        test("meets the other suite") {
            Meeting.both.countDown()
            expect(Meeting.both.await(10, TimeUnit.SECONDS))
        }
    }
}

object OtherMeetingSuite : Suite() {
    init {
        test("meets the other suite") {
            Meeting.both.countDown()
            expect(Meeting.both.await(10, TimeUnit.SECONDS))
        }
    }
}

/** When each turn suite's test went in and came out. */
object Turns {
    val seen = ConcurrentLinkedQueue<String>()

    fun take(name: String) {
        seen += "$name in"
        // Long enough for a suite started beside it to go in before it comes out.
        Thread.sleep(50)
        seen += "$name out"
    }
}

object FirstTurnSuite : Suite() {
    init {
        test("takes a turn") {
            Turns.take("first")
            expect("first out" in Turns.seen)
        }
    }
}

object IsolatedTurnSuite : Suite(isolated = true) {
    init {
        test("takes a turn alone") {
            Turns.take("isolated")
            expect("isolated out" in Turns.seen)
        }
    }
}

object LastTurnSuite : Suite() {
    init {
        test("takes a turn") {
            Turns.take("last")
            expect("last out" in Turns.seen)
        }
    }
}
