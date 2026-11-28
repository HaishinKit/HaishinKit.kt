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
        exclusiveContent {
            forRepository {
                maven { url = uri(providers.gradleProperty("vqmetrixRepository").orElse("https://jitpack.io").get()) }
            }
            filter { includeModule("com.github.HaishinKit", "VQMetrix") }
        }
    }
}

rootProject.name = "HaishinKit"
include(":app")
include(":compose")
include(":haishinkit")
include(":mcp")
include(":lottie")
include(":rnnoise")
include(":rtmp")
include(":srt")
include(":vulkan")
include(":rtc")

include(":vqmetrix-example")
project(":vqmetrix-example").projectDir = file("app/vqmetrix")

// Explicit source override for VQMetrix development; normal builds use the published artifact.
providers.gradleProperty("vqmetrixTransferDir").orNull?.let { directory ->
    include(":vqmetrix-transfer")
    project(":vqmetrix-transfer").projectDir = file(directory)
}
