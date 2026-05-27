plugins {
    id("fluxit.kmp.library")
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "dev.franzueto.fluxit.shared.data"
}

sqldelight {
    databases {
        create("FluxItDatabase") {
            packageName.set("dev.franzueto.fluxit.shared.data.db")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":core:core-utils"))
            implementation(libs.bundles.coroutines)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.bundles.sqldelight.runtime)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.bundles.testing.shared)
        }
        // JVM-side in-memory SQLite driver for the §3/§4 smoke test. iOS-side
        // NativeSqliteDriver-in-memory wiring lands with the §10 test pyramid.
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.sqldelight.jvm.driver)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────
// Schema dump pipeline (ADR-006). schema.sql is the human-readable
// authoritative view of the on-disk schema; generateSchemaSql writes it
// from the .sq DDL portions, verifySchemaInSync re-runs the generator
// in-memory and fails if the committed file would diverge. Wired into
// :shared:data:check so CI catches drift PR-time. Mirrors Phase 02's
// verifyTokensInSync / verifyIconsInSync pattern.
//
// Logic is inlined in two doLast blocks (rather than a shared script
// function) because Gradle's configuration cache rejects script-captured
// function references. The duplication is ~15 lines and the alternative
// (a custom task type in build-logic) is more API surface than one rule
// earns.
// ────────────────────────────────────────────────────────────────────────

val sqDelightDir = layout.projectDirectory.dir("src/commonMain/sqldelight")
val schemaSqlFile = layout.projectDirectory.file("schema.sql")

val generateSchemaSql by tasks.registering {
    group = "verification"
    description = "Generate shared/data/schema.sql from the .sq DDL portions (ADR-006)."
    inputs.dir(sqDelightDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(schemaSqlFile)
    val rootDir = sqDelightDir.asFile
    val outFile = schemaSqlFile.asFile
    doLast {
        val queryLabel = Regex("^[A-Za-z][A-Za-z0-9_]*:\\s*$")
        val sql =
            rootDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "sq" }
                .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
                .joinToString("\n\n") { file ->
                    val lines = file.readLines()
                    val firstQuery = lines.indexOfFirst { queryLabel.matches(it) }
                    val ddl =
                        (if (firstQuery == -1) lines else lines.subList(0, firstQuery))
                            .joinToString("\n")
                            .trimEnd()
                    "-- ${file.relativeTo(rootDir).invariantSeparatorsPath}\n\n$ddl"
                }
        outFile.writeText(sql + "\n")
    }
}

val verifySchemaInSync by tasks.registering {
    group = "verification"
    description = "Fail if shared/data/schema.sql diverges from the .sq DDL (ADR-006)."
    // When a single Gradle invocation runs both generateSchemaSql and check,
    // Gradle's parallel executor may schedule verifySchemaInSync against the
    // pre-generation schema.sql contents. mustRunAfter pins the order without
    // forcing a hard dependency (verify must still pass when run alone).
    mustRunAfter(generateSchemaSql)
    inputs.dir(sqDelightDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(schemaSqlFile).withPathSensitivity(PathSensitivity.RELATIVE)
    val rootDir = sqDelightDir.asFile
    val outFile = schemaSqlFile.asFile
    doLast {
        val queryLabel = Regex("^[A-Za-z][A-Za-z0-9_]*:\\s*$")
        val expected =
            rootDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "sq" }
                .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
                .joinToString("\n\n") { file ->
                    val lines = file.readLines()
                    val firstQuery = lines.indexOfFirst { queryLabel.matches(it) }
                    val ddl =
                        (if (firstQuery == -1) lines else lines.subList(0, firstQuery))
                            .joinToString("\n")
                            .trimEnd()
                    "-- ${file.relativeTo(rootDir).invariantSeparatorsPath}\n\n$ddl"
                } + "\n"
        val actual = outFile.readText()
        if (expected != actual) {
            throw GradleException(
                "shared/data/schema.sql is out of sync with the .sq DDL. " +
                    "Run ./gradlew :shared:data:generateSchemaSql and commit the result.",
            )
        }
    }
}

tasks.named("check") { dependsOn(verifySchemaInSync) }
