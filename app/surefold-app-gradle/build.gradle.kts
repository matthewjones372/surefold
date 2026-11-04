plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("surefoldWiring") {
            id = "dev.surefold.wiring"
            implementationClass = "dev.surefold.app.gradle.SurefoldWiringPlugin"
            displayName = "surefold-app wiring"
            description = "Checks a project's surefold-app graphs as it compiles, and renders each one."
        }
        create("surefoldForks") {
            id = "dev.surefold.compiler"
            implementationClass = "dev.surefold.app.gradle.SurefoldForksPlugin"
            displayName = "surefold compiler checks"
            description = "Fails a compile where a Deferred, Channel or Scope outlives the scope that ends it."
        }
    }
}

// `java-gradle-plugin` already publishes this module as `pluginMaven`, with the marker beside it.
// Configuring it as a plain Kotlin library would add a second publication at the same coordinates —
// harmless installing locally, a duplicate artifact the Central Portal rejects.
mavenPublishing {
    configure(
        com.vanniktech.maven.publish.GradlePlugin(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
            sourcesJar = true,
        ),
    )
}

// The plugin is tested by running a build, so the test project needs the very surefold-app this repo
// builds — not one resolved from a repository, which would test the last release instead.
val surefoldAppUnderTest: Configuration by configurations.creating

dependencies {
    // For `KotlinCompilerPluginSupportPlugin`. The API artifact rather than the plugin itself: this
    // jar is applied alongside whatever Kotlin plugin the consumer chose, not in front of it.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${rootProject.extra["surefoldKotlinVersion"]}")

    testImplementation(gradleTestKit())
    surefoldAppUnderTest(project(":surefold-app"))
    // The module here whose factories the reader knows by name. A build that compiles a graph using
    // them is the only thing that catches the reader's copy of their dependencies going stale, and
    // a stale copy is the one fault the design cannot tolerate: a red line under working code.
    // surefold-integrations checks the factories of its own surefold-app modules the same way.
    surefoldAppUnderTest(project(":surefold-app-actor"))
    // Not a factory the reader knows: Hub.publish is one of the effects the step checker refuses (spec 0210).
    surefoldAppUnderTest(project(":surefold-stream"))
    testImplementation(project(":surefold-assert"))
}

// The checker is resolved from a repository at compile time, so the plugin has to name a version.
// Generated rather than written down, so cutting a release does not leave a plugin pointing at the
// release before it.
val checkerCoordinates = tasks.register<WriteProperties>("checkerCoordinates") {
    destinationFile.set(layout.buildDirectory.file("generated/surefold/checker.properties"))
    property("group", project.group.toString())
    property("artifact", "surefold-app-compiler")
    property("forksArtifact", "surefold-compiler")
    property("version", project.version.toString())
}

sourceSets.main {
    resources.srcDir(checkerCoordinates.map { it.destinationFile.get().asFile.parentFile })
}

// The compiler plugin is resolved from a repository by coordinates, not handed over as a file, so a
// TestKit build needs one to resolve it from. The root project already declares `local`.
val checkerRepo: Provider<Directory> = rootProject.layout.buildDirectory.dir("repo")

tasks.test {
    inputs.files(surefoldAppUnderTest).withPropertyName("surefoldAppUnderTest")
    val reader = rootProject.file(
        "app/surefold-app-compiler/src/main/kotlin/dev/surefold/app/compiler/Reader.kt",
    )
    inputs.file(reader).withPropertyName("reader")
    // Both halves, resolved by a TestKit build the way a consumer's build resolves them. Injecting
    // this jar with `withPluginClasspath` instead would put it in a classloader of its own, where
    // the Kotlin plugin cannot see that it implements `KotlinCompilerPluginSupportPlugin`.
    dependsOn(
        ":surefold-app-compiler:publishAllPublicationsToLocalRepository",
        ":surefold-compiler:publishAllPublicationsToLocalRepository",
        "publishAllPublicationsToLocalRepository",
    )
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.underTest=" +
                    surefoldAppUnderTest.joinToString(File.pathSeparator) { it.absolutePath },
                "-Dsurefold.checker.repo=" + checkerRepo.get().asFile.absolutePath,
                "-Dsurefold.checker.version=" + version,
                "-Dsurefold.checker.reader=" + reader.absolutePath,
            )
        },
    )
}

tasks.test {
    // A TestKit test starts a Gradle build of its own: on a cold machine that is a distribution to
    // download and a dependency graph to resolve, and the 60s this build gives every other test is
    // the wrong order of magnitude. It passed here and timed out on the first CI run there was.
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
    systemProperty("surefold.junit.timeout.default", "10m")
    // The plugin compiles for 21, as it runs inside whatever JDK Gradle is on, but a project using surefold-app
    // compiles for 25, surefold-app's floor: its TestKit builds run on 25, which their Kotlin then targets.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

// What runs inside the build tools stays at 21, but only the main code does: the tests check it with
// surefold-assert, which is built for 25 as every library here is, so they compile and run on 25.
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
