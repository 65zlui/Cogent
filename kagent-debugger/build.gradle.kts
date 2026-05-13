plugins {
    kotlin("jvm") version "2.3.0"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kagent-protocol"))
    implementation(project(":kagent-core"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
