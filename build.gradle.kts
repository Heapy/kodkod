import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm").version("2.4.0")
    kotlin("plugin.serialization").version("2.4.0")
    application
}

group = "io.heapy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
