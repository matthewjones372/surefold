/**
 * surefold-jobs under load from Proofload (spec 0220): jobs enqueued at a fixed rate against one Postgres in a
 * container, drained by 1, 4 and 16 workers. Nothing here is published, and nothing in `build` runs it:
 * `./gradlew :surefold-jobs-load:run`, optionally with `--args="<jobs per second> <seconds>"`.
 */

plugins {
    application
}

dependencies {
    implementation(project(":surefold-jobs"))
    implementation("io.github.matthewjones372:proofload-core:0.1.0-rc5")
    implementation("io.github.matthewjones372:proofload-engine:0.1.0-rc5")
    implementation("org.liquibase:liquibase-core:4.32.0")
    implementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:7.1.0")
}

application {
    mainClass.set("dev.surefold.jobs.load.LoadKt")
}
