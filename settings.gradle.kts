pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version providers.gradleProperty("kotlin.version").get()
        kotlin("plugin.serialization") version providers.gradleProperty("kotlin.version").get()
        id("com.github.johnrengelman.shadow") version providers.gradleProperty("shadowjar.version").get()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}

rootProject.name = "paperTemplateKt"