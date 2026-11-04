package dev.surefold.junit

import com.sun.management.HotSpotDiagnosticMXBean
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files

/**
 * Every thread's stack, virtual ones included, on stderr, which the test's report holds (spec 0308).
 * `Thread.getAllStackTraces()` leaves virtual threads out, and nearly every thread a hung test waits on is one.
 */
internal object ThreadDump {

    fun toStderr(why: String) {
        // dumpThreads refuses a path that exists, so the file is named inside a directory made for it.
        val dir = Files.createTempDirectory("surefold-threads")
        val dump = dir.resolve("threads.txt")
        try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                .dumpThreads(dump.toString(), ThreadDumpFormat.TEXT_PLAIN)
            System.err.println("$why; every thread, virtual ones included:")
            System.err.println(Files.readString(dump))
        } catch (failed: IOException) {
            // The timeout is what the test reports; a dump that could not be written must not replace it.
            System.err.println("$why; the threads could not be dumped: $failed")
        } finally {
            Files.deleteIfExists(dump)
            Files.delete(dir)
        }
    }
}
