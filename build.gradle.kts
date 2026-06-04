import org.gradle.api.plugins.jvm.JvmTestSuite
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm").version("2.4.0")
    kotlin("plugin.serialization").version("2.4.0")
    application
    `jvm-test-suite`
}

group = "io.heapy"
version = "1.0.0"

val junitVersion = "6.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
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
            }

            targets.all {
                testTask.configure {
                    description = "Runs Docker-backed end-to-end tests"
                    group = "verification"
                    shouldRunAfter(tasks.test)
                    maxParallelForks = 1
                    outputs.upToDateWhen { false }

                    systemProperty("kodkod.e2e.useCurrentDocker", providers.gradleProperty("kodkod.e2e.useCurrentDocker").orNull ?: "false")
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
