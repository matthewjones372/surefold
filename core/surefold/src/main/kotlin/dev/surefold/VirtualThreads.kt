package dev.surefold

import java.util.concurrent.Executor

/** The executor a fork runs on when nothing names another: one virtual thread per fork. */
object VirtualThreads : Executor {
    override fun execute(command: Runnable) {
        Thread.ofVirtual().start(command)
    }
}

// Why every combinator comes in two forms rather than one with a defaulted `on`: Kotlin maps positional
// arguments in order, so `parZip({ a }, { b }) { … }` would hand its first branch to a default sitting in
// front of it, and no 0003 call site would compile. arrow-fx-coroutines pairs its `context` overload with a
// plain one for the same reason. `scope` and `async` need no pair, because their block is a trailing lambda
// and a default in front of it is reachable.
