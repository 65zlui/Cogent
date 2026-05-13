plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "cogent"

include("kagent-core")
include("kagent-protocol")
include("kagent-debugger")
include("kagent-inspector")
include("kagent-demo")
