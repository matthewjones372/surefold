// The compiler is on the compile classpath only: it is already there when the plugin runs, and a
// published dependency on it would put a second copy of the Kotlin compiler in every consumer.
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["surefoldKotlinVersion"]}")
    // A test compiles snippets in this JVM, so it brings a compiler, and surefold for the snippets to call.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["surefoldKotlinVersion"]}")
    testImplementation(project(":surefold"))
    testImplementation(project(":surefold-assert"))
}

// What this was built against, written into the jar so the plugin can compare it with the compiler it finds itself in,
// as surefold-app-compiler does. Named for this plugin: a compiler loads every plugin jar into one classloader, where
// two resources of one name would answer for each other.
val builtFor =
    tasks.register<WriteProperties>("builtFor") {
        destinationFile.set(layout.buildDirectory.file("generated/surefold-forks/surefold-forks.properties"))
        property("kotlin", rootProject.extra["surefoldKotlinVersion"].toString())
    }

sourceSets.main {
    resources.srcDir(builtFor.map { it.destinationFile.get().asFile.parentFile })
}

// A snippet's compiler loads the plugin as a consumer's does, from its classes and its service files.
tasks.withType<Test>().configureEach {
    val plugin = sourceSets.main.get().output
    inputs.files(plugin).withPropertyName("plugin")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.compiler.plugin=" +
                    plugin.files.filter { it.exists() }.joinToString(File.pathSeparator) { it.absolutePath },
            )
        },
    )
}

// What runs inside the build tools stays at 21, but only the main code does: the tests compile against surefold,
// which is built for 25 as every library here is, so they compile and run on 25.
val testJdk = JavaLanguageVersion.of(25)

listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, testJdk.asInt())
    }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor { languageVersion.set(testJdk) })
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}

tasks.named<JavaCompile>("compileTestJava") {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(testJdk) })
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(testJdk) })
}
