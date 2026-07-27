import org.gradle.api.plugins.jvm.JvmTestSuite
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm").version("2.4.10")
    kotlin("plugin.serialization").version("2.4.10")
    application
    `jvm-test-suite`
    // The DockerClient contract suite has to be visible to BOTH test source sets: `test` runs it
    // against FakeDockerClient, `e2eTest` against DockerApi on a real daemon. Test fixtures are the
    // one place both can see, and they are packaged separately from the production jar.
    `java-test-fixtures`
}

group = "io.heapy"
version = "1.0.0"

val junitVersion = "6.1.2"
val serializationVersion = "1.11.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // The contract suite is JUnit tests over DockerClient, so it needs both on its own classpath;
    // main exposes serialization only as `implementation`, hence the re-declaration.
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(junitVersion)
        }

        val e2eTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(junitVersion)

            dependencies {
                implementation(project())
                // The recorder calls DockerApi (which returns kotlinx JsonObject) and writes fixture
                // JSON; main exposes these only as `implementation`, so re-declare for this suite.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                // The same DockerClient contract `test` runs against the fake, run here against a
                // real daemon — that pairing is the whole point of putting it in fixtures.
                implementation(testFixtures(project()))
            }

            targets.all {
                testTask.configure {
                    description = "Runs Docker-backed end-to-end tests"
                    group = "verification"
                    shouldRunAfter(tasks.test)
                    maxParallelForks = 1
                    outputs.upToDateWhen { false }

                    systemProperty("kodkod.e2e.useCurrentDocker", providers.gradleProperty("kodkod.e2e.useCurrentDocker").orNull ?: "false")
                    systemProperty("kodkod.e2e.record", providers.gradleProperty("kodkod.e2e.record").orNull ?: "false")
                    systemProperty("kodkod.e2e.keepDind", providers.gradleProperty("kodkod.e2e.keepDind").orNull ?: "false")
                    systemProperty("kodkod.e2e.dindName", providers.gradleProperty("kodkod.e2e.dindName").orNull ?: "kodkod-e2e-dind")
                    systemProperty("kodkod.e2e.dindImage", providers.gradleProperty("kodkod.e2e.dindImage").orNull ?: "docker:dind")
                    systemProperty("kodkod.e2e.dindPort", providers.gradleProperty("kodkod.e2e.dindPort").orNull ?: "12375")

                    testLogging {
                        events("passed", "skipped", "failed")
                        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    }
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

application {
    applicationName = "kodkod"
    mainClass = "io.heapy.kodkod.MainKt"
}
