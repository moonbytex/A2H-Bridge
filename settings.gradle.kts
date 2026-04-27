pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.10"
        id("org.jetbrains.intellij.platform") version "2.1.0"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "A2H-Bridge"

