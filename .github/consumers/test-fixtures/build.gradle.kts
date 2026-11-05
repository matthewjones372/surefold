// Resolves the test fixtures surefold-integrations and surefold-platform take from core (spec 0301), and fails
// unless each arrives with the dependencies its fixtures need. snapshot.yml runs it against GitHub Packages
// after every publish; `-PsurefoldRepo=file://.../build/repo` runs it against a local publish:
//
//   ./gradlew -p .github/consumers/test-fixtures checkFixtures -PsurefoldRepo=<url> -PsurefoldVersion=<version>

plugins {
    `java-library`
}

val surefoldRepo = providers.gradleProperty("surefoldRepo").get()
val surefoldVersion = providers.gradleProperty("surefoldVersion").get()

repositories {
    mavenCentral()
    maven {
        url = uri(surefoldRepo)
        // GitHub Packages asks for a token even to read a public package: snapshot.yml passes the workflow's.
        if (url.scheme == "https") {
            name = "githubPackages"
            credentials(PasswordCredentials::class)
        }
    }
}

// Core's fixtures that the other two repositories' tests use today through `testFixtures(project(...))`.
val fixtureModules = listOf("surefold-actor", "surefold-actor-remote", "surefold-actor-journal-jdbc")

dependencies {
    fixtureModules.forEach { testImplementation(testFixtures("dev.surefold:$it:$surefoldVersion")) }
}

// Surefold's libraries are built for 25; this build only resolves them, whatever JDK Gradle runs on.
configurations.testRuntimeClasspath {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
}

// Each module's fixtures jar and its main jar, and what the journal's Postgres fixture runs on: a fixture whose
// dependencies were left out of the published metadata resolves here, then fails in a consumer's first test.
val expected =
    fixtureModules.flatMap {
        listOf(Regex("$it-\\d.*-test-fixtures\\.jar"), Regex("$it-\\d.*(?<!-test-fixtures)\\.jar"))
    } +
        listOf("postgresql", "testcontainers-postgresql", "liquibase-core").map { Regex("$it-\\d.*\\.jar") }

tasks.register("checkFixtures") {
    val classpath = configurations.testRuntimeClasspath
    doLast {
        val names = classpath.get().files.map { it.name }
        val missing = expected.filter { pattern -> names.none(pattern::matches) }
        check(missing.isEmpty()) { "Not resolved from $surefoldRepo: $missing\nResolved: $names" }
        println("Resolved the test fixtures of $fixtureModules at $surefoldVersion from $surefoldRepo")
    }
}
