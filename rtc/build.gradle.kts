plugins {
    id("maven-publish")
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.jetbrainsDokka)
}

android {
    namespace = "com.haishinkit.rtc"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild.cmake {
            providers.gradleProperty("rtcDatachannelSourceDir").orNull?.let {
                arguments += "-DFETCHCONTENT_SOURCE_DIR_DATACHANNEL=$it"
            }
            providers.gradleProperty("rtcMbedtlsSourceDir").orNull?.let {
                arguments += "-DFETCHCONTENT_SOURCE_DIR_MBEDTLS=$it"
            }
        }
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    sourceSets {
        getByName("test").java.srcDir("src/socketTest/java")
        getByName("androidTest").java.srcDir("src/socketTest/java")
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
        artifactId = "rtc"
        version = rootProject.ext["PUBLISH_VERSION"] as String
    }
}

dependencies {
    api(project(":haishinkit"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("rtcNativeLibraryDir").orNull?.let {
        systemProperty("java.library.path", it)
        systemProperty("haishinkit.rtc.nativeTests", "true")
    }
}

dokka.dokkaSourceSets.configureEach {
    if (name == "main") includes.from(project.layout.projectDirectory.file("module-docs.md"))
}
