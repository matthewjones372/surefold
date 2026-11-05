// The compiler is on the compile classpath only: it is already there when the plugin runs, and a
// published dependency on it would put a second copy of the Kotlin compiler in every consumer.
//
// The version comes from the root rather than a number written here: a plugin compiled against one
// compiler and loaded into another fails at a consumer's compile, or quietly in their editor.
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["surefoldKotlinVersion"]}")
    // A test runs outside the compiler, so it has to bring one; `compileOnly` does not reach here.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["surefoldKotlinVersion"]}")
    testImplementation(project(":surefold-assert"))
}

// What this was built against, written into the jar so the checker can compare it with the compiler
// it finds itself in. A plugin loaded into a compiler it was not built for is the failure this
// module keeps finding new ways to have, and the editor's version of it is silent.
val builtFor =
    tasks.register<WriteProperties>("builtFor") {
        destinationFile.set(layout.buildDirectory.file("generated/surefold/surefold-compiler.properties"))
        property("kotlin", rootProject.extra["surefoldKotlinVersion"].toString())
    }

sourceSets.main {
    resources.srcDir(builtFor.map { it.destinationFile.get().asFile.parentFile })
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
