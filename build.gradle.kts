plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

group = "pl.infakt"
version = project.findProperty("version") as String? ?: "0.1.0-SNAPSHOT"
description = "Kotlin port of envato/double_entry: double-entry accounting for the JVM"

kotlin {
    jvmToolchain(
        libs.versions.java
            .get()
            .toInt(),
    )
}

// The parity harness: replays oracle/scenarios against the port (oracle/README.md). It sees main's internals.
val parity: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[parity.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[parity.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

kotlin.target.compilations["parity"].associateWith(kotlin.target.compilations["main"])

// The harness's own tests (src/test/.../parity) see its classes and its JSON library.
sourceSets.test {
    compileClasspath += parity.output
    runtimeClasspath += parity.output
}

configurations.testImplementation.get().extendsFrom(configurations[parity.implementationConfigurationName])

// The optional benchmark (spec/performance): run it with ./gradlew benchmark, never part of test, check or build.
// It takes its database and the spec accounts from the tests' support (TestDatabase, SpecAccounts).
val benchmark: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations[benchmark.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[benchmark.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.json)
    implementation(libs.kotlinx.serialization.json)

    add(parity.implementationConfigurationName, libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The size of JackHammerTest (script/jack_hammer's options): -Ddoubleentry.jackhammer.<name>=<value> on the command
    // line goes to the test JVM.
    listOf("threads", "transfers", "rounds", "seed").forEach { name ->
        val property = "doubleentry.jackhammer.$name"
        providers.systemProperty(property).orNull?.let { systemProperty(property, it) }
    }
}

// Not a dependency of test or check: run it with oracle/replay.
// The schema area's executor takes its databases from the tests' TestDatabase (src/test/.../support), so the run
// has the test output and its runtime classpath. Only at run time: test compiles against parity, not the other way.
tasks.register<JavaExec>("parity") {
    group = "verification"
    description = "Replays the oracle's scenarios of the areas on in oracle/enabled.json against the port."
    classpath = parity.runtimeClasspath + sourceSets.test.get().runtimeClasspath
    mainClass.set("pl.infakt.doubleentry.parity.ReplayKt")
    workingDir = rootDir
}

// Not a dependency of test, check or build: the port of spec/performance, run it with ./gradlew benchmark.
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Times 100 transfers in each profile of the gem's performance spec and prints the times."
    classpath = benchmark.runtimeClasspath
    mainClass.set("pl.infakt.doubleentry.benchmark.TransferBenchmark")
    workingDir = rootDir
}
