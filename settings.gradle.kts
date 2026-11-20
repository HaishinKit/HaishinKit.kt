pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HaishinKit"
include(":app")
include(":compose")
include(":haishinkit")
include(":lottie")
include(":rtmp")
include(":srt")
include(":vulkan")
include(":rtc")

// Optional developer tool when the VQMetrix checkout is available beside this repository.
val vqmetrixDirectory = file(providers.gradleProperty("vqmetrixDir").orElse("../VQMetrix/Android/producer").get())
if (vqmetrixDirectory.resolve("build.gradle.kts").isFile) {
    include(":vqmetrix-producer", ":vqmetrix-example")
    project(":vqmetrix-example").projectDir = file("app/vqmetrix")
    project(":vqmetrix-producer").projectDir = vqmetrixDirectory
}
