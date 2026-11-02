package dev.surefold.app

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import kotlin.reflect.KType
import kotlin.system.exitProcess

/** One application, checked. */
private class Wired(val name: String, val findings: List<Finding>, val provided: Set<KType>)

/** What the check found: what to print, and whether the build should stop. */
data class Checked(val report: String, val failed: Boolean)

/**
 * Every [SurefoldApp] in [classes], checked against the root it declares.
 *
 * [failOn] is the severity a finding has to reach to stop a build, so a codebase that wants its
 * warnings fatal passes `WARN`. A diagram of each graph is written into [diagrams] where one is
 * given, which is the drawing the IDE renders.
 *
 * [sources] are the source directories a finding's site is resolved against. Given them, the report
 * is the compiler's own `e: file://...` diagnostic, which the IDE turns into a clickable entry in
 * the Build window; without them it is the prose report, which a person reads and then goes
 * looking. Pass them.
 */
fun checkWiring(
    classes: List<File>,
    diagrams: File? = null,
    failOn: Severity = Severity.FAIL,
    sources: List<File> = emptyList(),
): Checked {
    val checked = apps(classes).map { app ->
        diagrams?.let { into ->
            into.mkdirs()
            File(into, "${nameOf(app)}.mmd").writeText(app.module.render())
        }
        Wired(nameOf(app), app.module.findings(app.root), app.module.nodes.keys)
    }

    val failed = checked.any { wired -> wired.findings.any { it.severity >= failOn } }
    val found = checked.filter { it.findings.isNotEmpty() }

    val said = when {
        sources.isEmpty() -> found.map { "${it.name}\n\n${it.findings.report()}" }
        else -> found.map { it.findings.diagnostics(sources, it.provided) }
    }

    return Checked(said.joinToString("\n"), failed)
}

/**
 * The applications declared in [classes], found by their supertype.
 *
 * Loaded without initialising, so scanning a directory of unrelated classes runs none of their
 * static state; only a match is initialised, and initialising one runs the expression that
 * assembles its graph but none of the recipes in it.
 */
internal fun apps(classes: List<File>): List<SurefoldApp<*>> =
    classes.filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.extension == "class" }.map { classNameOf(root, it) } }
        .sorted()
        .mapNotNull { name -> loaded(name) }
        .filter { SurefoldApp::class.java.isAssignableFrom(it) && it != SurefoldApp::class.java }
        .mapNotNull { declared(it) }

private fun classNameOf(root: File, classFile: File): String =
    classFile.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')

// A class that will not link is not an application, and a build tool that stopped on one would stop
// on every optional dependency a project left off its runtime classpath.
private fun loaded(name: String): Class<*>? =
    try {
        Class.forName(name, false, SurefoldApp::class.java.classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

/** An application is an `object`, and a class with a constructor taking nothing also answers. */
private fun declared(type: Class<*>): SurefoldApp<*>? = (instanceOf(type) ?: constructed(type)) as? SurefoldApp<*>

private fun instanceOf(type: Class<*>): Any? =
    try {
        type.getDeclaredField("INSTANCE").takeIf { Modifier.isStatic(it.modifiers) }?.get(null)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }

private fun constructed(type: Class<*>): Any? =
    try {
        type.getDeclaredConstructor().newInstance()
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }

private fun nameOf(app: SurefoldApp<*>): String = app::class.simpleName ?: app::class.java.name

/**
 * Standard error as UTF-8 rather than whatever the platform defaults to.
 *
 * From JDK 19 `System.err` follows `stderr.encoding`, which is the native encoding when the stream is
 * redirected — so on a runner that is not UTF-8 the report's own bullet arrives as a question mark,
 * and `-Dfile.encoding` does not fix it.
 */
private fun said(): PrintStream = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)

/**
 * The check as a process, for a build to run against a project's own class output.
 *
 * `classes` and `sources` are separated by the platform's path separator; `diagrams` is a directory
 * or empty for none; `failOn` is a [Severity] name.
 */
fun main(args: Array<String>) {
    val classes = paths(args.getOrElse(0) { "" })
    val diagrams = args.getOrElse(1) { "" }.takeIf { it.isNotBlank() }?.let(::File)
    val failOn = Severity.valueOf(args.getOrElse(2) { Severity.FAIL.name })
    val sources = paths(args.getOrElse(3) { "" })

    val checked = checkWiring(classes, diagrams, failOn, sources)
    if (checked.report.isNotBlank()) said().println(checked.report)
    exitProcess(if (checked.failed) 1 else 0)
}

private fun paths(arg: String): List<File> =
    arg.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
