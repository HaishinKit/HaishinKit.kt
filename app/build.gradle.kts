plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.haishinkit.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.haishinkit.app"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("1.0").get()
        buildConfigField("boolean", "VQMETRIX_AVAILABLE", (rootProject.findProject(":vqmetrix-example") != null).toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (providers.environmentVariable("APK_KEYSTORE_PATH").isPresent) {
            create("distribution") {
                storeFile = file(providers.environmentVariable("APK_KEYSTORE_PATH").get())
                storePassword = providers.environmentVariable("APK_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("APK_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("APK_KEY_PASSWORD").get()
            }
        }
    }

    testBuildType = "uiTest"

    buildTypes {
        configureEach {
            // Exercise consumer rules during development as well as distribution.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // AGP disables optimization/obfuscation for debuggable apps.
            isDebuggable = false
            proguardFiles("proguard-debug.pro")
        }
        // White-box Compose tests call internals removed by whole-app optimization.
        create("uiTest") {
            initWith(getByName("debug"))
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "debug"
        }
        release {
            signingConfig = signingConfigs.findByName("distribution")
        }
    }
    sourceSets.getByName("uiTest").java.srcDir("src/release/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17,
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Netty's per-JAR indexes are not used in an APK; retain all module version entries.
            excludes += "/META-INF/INDEX.LIST"
            merges += "/META-INF/io.netty.versions.properties"
        }
        jniLibs.keepDebugSymbols += "**/libhksrt.so"
    }
}

androidComponents {
    // Keep debug-only frame-capture regressions in the host test suite too.
    beforeVariants(selector().withBuildType("debug")) { variant ->
        (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {

    if (rootProject.findProject(":vqmetrix-example") != null) {
        implementation(project(":vqmetrix-example"))
    }
    implementation(project(":haishinkit"))
    implementation(project(":vulkan"))
    implementation(project(":rtmp"))
    implementation(project(":srt"))
    implementation(project(":rtc"))
    implementation(project(":lottie"))
    implementation(project(":compose"))
    implementation(libs.accompanist.permissions)
    implementation(libs.accompanist.pager.indicators)
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    add("uiTestImplementation", libs.androidx.ui.test.manifest)
    // LeakCanary rejects non-debuggable APKs; keep it with the debugger test build.
    add("uiTestImplementation", libs.leakcanary.android)
    debugImplementation(libs.mcp.server)
    debugImplementation(libs.ktor.server.netty)
    debugImplementation(libs.ktor.server.content.negotiation)
    debugImplementation(libs.ktor.serialization.json)
}
