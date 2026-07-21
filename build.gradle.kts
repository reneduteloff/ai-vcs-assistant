import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "com.aivcsassistant"
version = effectivePluginVersion()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = "262.*"
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    patchPluginXml {
        changeNotes.set("Build 253-262 compatibility; Java 17; no direct Git4Idea API dependency.")
    }
}

fun effectivePluginVersion(): String {
    val currentVersion = providers.gradleProperty("pluginVersion").get()
    return if (isBuildPluginRequested()) bumpPatchVersion(currentVersion) else currentVersion
}

fun isBuildPluginRequested(): Boolean =
    gradle.startParameter.taskNames.any { taskName ->
        taskName == "buildPlugin" || taskName.endsWith(":buildPlugin")
    }

fun bumpPatchVersion(currentVersion: String): String {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(currentVersion)
        ?: error("pluginVersion must use major.minor.patch format, for example 1.0.2.")
    val nextVersion = "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3].toInt() + 1}"
    val propertiesFile = rootProject.file("gradle.properties")
    val lines = propertiesFile.readLines()
    val updatedLines = lines.map { line ->
        if (line.startsWith("pluginVersion=")) "pluginVersion=$nextVersion" else line
    }
    propertiesFile.writeText(updatedLines.joinToString(System.lineSeparator()) + System.lineSeparator())
    return nextVersion
}
