plugins {
    kotlin("jvm") version "2.3.0" apply false
}

group = "com.cogent"
version = "0.6.2"
description = "Cogent: JVM-Native Agent Execution Protocol & Runtime"

subprojects {
    repositories {
        mavenCentral()
    }
}
