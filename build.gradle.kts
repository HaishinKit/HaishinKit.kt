// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.jetbrainsDokka)
    alias(libs.plugins.compose.compiler) apply false
    id("maven-publish")
    id("org.jlleitschuh.gradle.ktlint") version "12.2.0" apply true
}

rootProject.ext["PUBLISH_GROUP_ID"] = "com.github.HaishinKit"
rootProject.ext["PUBLISH_VERSION"] = "0.14.2"

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

dokka {
    dokkaSourceSets.configureEach {
        val moduleDocsFile = "module-docs.md"
        if (file(moduleDocsFile).exists()) {
            includes.from(moduleDocsFile)
        }
    }
    dokkaPublications.html {
        outputDirectory.set(rootDir.resolve("docs"))
        includes.from(project.layout.projectDirectory.file("README.md"))
    }
}

dependencies {
    dokka(project(":haishinkit:"))
    dokka(project(":compose:"))
    dokka(project(":lottie:"))
    dokka(project(":rtmp:"))
}
