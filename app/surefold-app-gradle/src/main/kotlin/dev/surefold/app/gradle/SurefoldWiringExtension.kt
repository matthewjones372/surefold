package dev.surefold.app.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/** What the check does when it finds something. */
abstract class SurefoldWiringExtension {

    /**
     * The severity that stops a build: `FAIL` for a missing key or a cycle alone, `WARN` for those and a duplicate key
     * or an unreached node too.
     *
     * A string rather than surefold-app's own `Severity`, so a build script's classpath does not have to carry the
     * library it is checking.
     */
    abstract val failOn: Property<String>

    /** Where a diagram of each application lands. */
    abstract val diagrams: DirectoryProperty

    /**
     * Whether the compiler says which applications it checked, as a warning per application.
     *
     * Off by default, because a build that is working has nothing to say. On, it answers the one question a silent
     * checker cannot be asked: whether it is running at all.
     */
    abstract val verbose: Property<Boolean>
}
