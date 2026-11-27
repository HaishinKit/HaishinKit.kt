plugins {
    id("maven-publish")
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.jetbrainsDokka)
}

android {
    namespace = "com.haishinkit.mcp"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    publishing.singleVariant("release") { withSourcesJar() }
}

afterEvaluate {
    publishing.publications.create<MavenPublication>("release") {
        from(components["release"])
        groupId = rootProject.ext["PUBLISH_GROUP_ID"] as String
        artifactId = "mcp"
        version = rootProject.ext["PUBLISH_VERSION"] as String
    }
}

dependencies {
    api(project(":haishinkit"))
    api(libs.mcp.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

dokka.dokkaSourceSets.configureEach {
    if (name == "main") includes.from(project.layout.projectDirectory.file("module-docs.md"))
}
