import org.jetbrains.dokka.gradle.DokkaTaskPartial

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.jetbrainsDokka)
    id("maven-publish")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply true
}

rootProject.ext["PUBLISH_GROUP_ID"] = "com.github.HaishinKit"
rootProject.ext["PUBLISH_VERSION"] = "0.14.2"

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    tasks.withType<DokkaTaskPartial>().configureEach {
        dokkaSourceSets.configureEach {
            val moduleDocsFile = "module-docs.md"
            if (file(moduleDocsFile).exists()) {
                includes.from(moduleDocsFile)
            }
        }
    }
}

tasks.dokkaHtmlMultiModule.configure {
    outputDirectory.set(File("docs"))
    includes.from("README.md")
}
