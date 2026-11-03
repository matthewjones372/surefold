// Messages, events and snapshots from a service's own @Serializable data classes (spec 0093): a codec for one class,
// a table of several under fixed tags, an ask, and the .proto the classes make. NoOtherDependenciesTest asserts the
// runtime classpath.

plugins {
    kotlin("plugin.serialization")
    // Only for ProtoSuite: protoc compiles the golden .proto, and its classes read what this module writes.
    id("com.google.protobuf") version "0.9.5"
}

val protobufVersion = "4.36.2"

dependencies {
    testImplementation(project(":surefold-assert"))
    // freePort(): a port below the ephemeral range, never handed out twice in a JVM (spec 0197).
    testImplementation(testFixtures(project(":surefold-actor-remote")))
    api(project(":surefold-actor-remote"))
    // Its protobuf format and schema generator are marked experimental: pinned to the version tested here.
    api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")

    testImplementation("com.google.protobuf:protobuf-java:$protobufVersion")
    // The same table written in CBOR: any kotlinx BinaryFormat, not ProtoBuf alone.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.11.0")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    // ProtoSuite compares what proto() writes with the golden file protoc compiles.
    val golden = layout.projectDirectory.file("src/test/proto/surefold/kotlinx/till.proto")
    inputs.file(golden).withPropertyName("golden")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.actor.remote.kotlinx.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.actor.remote.kotlinx.golden=${golden.asFile.absolutePath}",
            )
        },
    )
}
