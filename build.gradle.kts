plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow")
}

version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://maven.enginehub.org/repo/")
    maven("https://nexus.frengor.com/repository/public/")
}

dependencies {
    compileOnly("com.mojang:brigadier:1.0.18")
    compileOnly(dependencyNotation = "io.papermc.paper:paper-api:${project.property("paper.version")}")
    implementation("com.github.moruch4nn:paperallinone:${project.property("paperallinone.version")}")
    implementation("dev.jorel:commandapi-bukkit-kotlin:${project.property("commandapi.version")}")
    implementation("dev.jorel:commandapi-bukkit-shade:${project.property("commandapi.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${project.property("kotlinx.serializer.version")}")
}

kotlin {
    jvmToolchain(17)
}

tasks {
    shadowJar {
        relocate("kotlin", "${path}.kotlin")
        relocate("dev.jorel.commandapi", "${path}.dev.jorel.commandapi")
        relocate("dev.mr3n.paperallinone", "${path}.dev.mr3n.paperallinone")
    }

    build {
        dependsOn("shadowJar")
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}