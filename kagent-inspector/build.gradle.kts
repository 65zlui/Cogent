plugins {
    kotlin("jvm") version "2.3.0"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kagent-debugger"))
    implementation(project(":kagent-protocol"))
}

tasks.test {
    useJUnitPlatform()
}
