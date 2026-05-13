plugins {
    kotlin("jvm") version "2.3.0"
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kagent-debugger"))
    implementation(project(":kagent-protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
    mainClass.set("com.cogent.demo.MainKt")
}
