package dev.surefold.kafka

import com.sun.management.HotSpotDiagnosticMXBean
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.PreInterruptCallback
import org.junit.jupiter.api.extension.PreInterruptContext
import java.lang.management.ManagementFactory
import java.nio.file.Files

/**
 * Every thread's stack on stderr before JUnit interrupts a test that timed out. JUnit's own dump reads
 * `Thread.getAllStackTraces()`, which leaves out virtual threads, and every Forks body and feed is one.
 */
class ThreadsOnTimeout : PreInterruptCallback {

    override fun beforeThreadInterrupt(preInterruptContext: PreInterruptContext, extensionContext: ExtensionContext) {
        val dir = Files.createTempDirectory("threads-on-timeout")
        // dumpThreads refuses a path that exists, so the file is named inside a directory made for it.
        val dump = dir.resolve("threads.txt")
        try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                .dumpThreads(dump.toString(), ThreadDumpFormat.TEXT_PLAIN)
            System.err.println("${extensionContext.displayName} timed out; every thread, virtual ones included:")
            System.err.println(Files.readString(dump))
        } finally {
            Files.deleteIfExists(dump)
            Files.delete(dir)
        }
    }
}
