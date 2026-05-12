plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "com.cogent"
version = "0.5.1"
description = "Cogent: JVM-Native Agent Execution Protocol & Runtime"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlinx Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.cogent.memory.MainKt")
}