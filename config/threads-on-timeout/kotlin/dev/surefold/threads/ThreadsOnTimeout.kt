package dev.surefold.threads

import com.sun.management.HotSpotDiagnosticMXBean
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.PreInterruptCallback
import org.junit.jupiter.api.extension.PreInterruptContext
import java.lang.management.ManagementFactory
import java.nio.file.Files

/**
 * Every thread's stack on stderr before Jupiter interrupts a test that timed out (specs 0130, 0308). JUnit's own
 * dump reads `Thread.getAllStackTraces()`, which leaves out virtual threads, and nearly every thread a hung test
 * waits on is one. The root build adds this source to every module's tests and turns autodetection on for it.
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
