plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

val captureVersion = providers.gradleProperty("appVersionName").orElse("1.0.0").get()
val transferVersion = providers.gradleProperty("vqmetrixTransferVersion").orElse("1.0.0").get()

android {
    namespace = "com.haishinkit.app.vqmetrix"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VQMETRIX_VERSION", "\"$captureVersion\"")
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    if (rootProject.findProject(":vqmetrix-transfer") != null) {
        implementation(project(":vqmetrix-transfer"))
    } else {
        implementation(
            "com.github.HaishinKit.VQMetrix:transfer:$transferVersion",
        )
    }
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    implementation(project(":haishinkit"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
