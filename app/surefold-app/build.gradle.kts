dependencies {
    api(project(":surefold"))
    testImplementation(project(":surefold-assert"))
}

val repoRoot = rootProject.projectDir.absolutePath

@Suppress("UNCHECKED_CAST")
val publishedModules = rootProject.extra["publishedModules"] as List<String>

@Suppress("UNCHECKED_CAST")
val describedModules = rootProject.extra["describedModules"] as List<String>

// The pages AgentDocsSuite holds to the published modules.
val agentDocs = files(
    rootProject.layout.projectDirectory.file("llms.txt"),
    rootProject.layout.projectDirectory.file("plugins/surefold/skills/surefold/SKILL.md"),
)

// The pages DocsSuite reads for import order and coordinates.
val guides = files(rootProject.layout.projectDirectory.file("README.md")) +
    fileTree(rootProject.layout.projectDirectory.dir("docs")) { include("*.md") }

// The page WiringDiagramSuite holds to what render answers, declared so editing
// it re-runs the test rather than leaving a stale answer behind.
val documentedDiagram = files(
    rootProject.layout.projectDirectory.file("docs/cookbook.md"),
    rootProject.layout.projectDirectory.file("docs/wiring.png.sha256"),
)

// The specs SpecsSuite holds to one number each. Their names are the input, so a spec renamed or added re-runs it.
val specs = fileTree(rootProject.layout.projectDirectory.dir("specs")) { include("**/*.md") }

// ThreadRuleSuite holds detekt's thread rule to the main sources it excuses (spec 0309): both are what it reads.
val threadRule = files(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
val mainSources = fileTree(rootProject.layout.projectDirectory) {
    include("**/src/main/kotlin/**/*.kt")
    exclude("**/build/**")
}

// CheckSuite runs discovery against this module's own test output, which is the only way to test a
// scan that reads class files rather than a list someone passed it.
val testClassDirs = sourceSets.test.get().output.classesDirs

tasks.test {
    inputs.files(documentedDiagram).withPropertyName("documentedDiagram")
    inputs.files(agentDocs).withPropertyName("agentDocs")
    inputs.files(guides).withPropertyName("guides")
    inputs.files(specs).withPropertyName("specs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(threadRule).withPropertyName("threadRule")
    inputs.files(mainSources).withPropertyName("mainSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("publishedModules", publishedModules)
    inputs.property("describedModules", describedModules)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.app.repoRoot=$repoRoot",
                "-Dsurefold.app.publishedModules=${publishedModules.joinToString(",")}",
                "-Dsurefold.app.describedModules=${describedModules.joinToString(",")}",
                "-Dsurefold.app.testClasses=" +
                    testClassDirs.files.joinToString(File.pathSeparator) { it.absolutePath },
            )
        },
    )
}
