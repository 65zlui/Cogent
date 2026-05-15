plugins {
    kotlin("jvm") version "2.3.0"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kagent-debugger"))
    implementation(project(":kagent-protocol"))
    implementation("org.json:json:20231013")
}

tasks.test {
    useJUnitPlatform()
}
