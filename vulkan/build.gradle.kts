plugins {
    id("maven-publish")
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.jetbrainsDokka)
}

android {
    namespace = "com.haishinkit.vulkan"
    compileSdk = 37

    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        shaders = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = rootProject.ext["PUBLISH_GROUP_ID"] as? String
                artifactId = "vulkan"
                version = rootProject.ext["PUBLISH_VERSION"] as? String
            }
        }
    }
}

dependencies {
    api(project(":haishinkit"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

abstract class CompileVulkanShaders : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shaders: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: org.gradle.process.ExecOperations

    @TaskAction
    fun compile() {
        val destination = outputDirectory.dir("shaders").get().asFile
        destination.mkdirs()
        shaders.filter { it.extension == "vert" || it.extension == "frag" }.forEach { shader ->
            execOperations.exec {
                commandLine(compiler.get().asFile, shader, "-o", destination.resolve("${shader.name}.spv"))
            }
        }
    }
}

val compileVulkanShaders =
    tasks.register<CompileVulkanShaders>("compileVulkanShaders") {
        shaders.from(fileTree("src/main/shaders") { include("*.vert", "*.frag", "*.glsl") })
        val host =
            when {
                System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
                System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
                else -> "linux-x86_64"
            }
        val executable = if (host.startsWith("windows")) "glslc.exe" else "glslc"
        compiler.set(androidComponents.sdkComponents.ndkDirectory.map { it.file("shader-tools/$host/$executable") })
        outputDirectory.set(layout.buildDirectory.dir("generated/vulkanAssets"))
    }

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(compileVulkanShaders, CompileVulkanShaders::outputDirectory)
}
