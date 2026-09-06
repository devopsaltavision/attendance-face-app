import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.google.services)
}

val localConfiguration = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun configuredValue(name: String): String =
    providers.environmentVariable(name).orNull
        ?: localConfiguration.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: ""

fun quotedBuildConfig(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

/**
 * Optional device-specific debug packaging. Without -PtargetAbi the debug APK remains universal
 * for emulator and other development targets; HF-X05 release APKs are always arm64-v8a only.
 */
val targetAbi = providers.gradleProperty("targetAbi").orNull?.trim()?.takeIf { it.isNotEmpty() }
if (targetAbi != null) {
    require(targetAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
        "Unsupported targetAbi: $targetAbi"
    }
}

android {
    namespace = "com.syntaxgenie.hfx05attendance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.syntaxgenie.hfx05attendance"
        minSdk = 23
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FINGERPRINT_API_KEY", quotedBuildConfig(configuredValue("FINGERPRINT_API_KEY")))
        buildConfigField("String", "FINGERPRINT_DEVICE_ID", quotedBuildConfig(configuredValue("FINGERPRINT_DEVICE_ID")))
        buildConfigField("String", "FINGERPRINT_BACKUP_KEY_BASE64",
            quotedBuildConfig(configuredValue("FINGERPRINT_BACKUP_KEY_BASE64")))
        buildConfigField("boolean", "FINGERPRINT_EMULATOR",
            configuredValue("FINGERPRINT_EMULATOR").toBooleanStrictOrNull()?.toString() ?: "false")
        buildConfigField("boolean", "FINGERPRINT_GUIDE_MODE",
            configuredValue("FINGERPRINT_GUIDE_MODE").toBooleanStrictOrNull()?.toString() ?: "false")

        externalNativeBuild {
            cmake {
                cppFlags += "-Wall"
            }
        }
    }

    buildTypes {
        debug {
            targetAbi?.let { abi ->
                ndk { abiFilters += abi }
            }
            val configuredUrl = configuredValue("FINGERPRINT_API_BASE_URL")
            val debugUrl = configuredUrl.ifBlank { "https://dev-yasitha--av-attendance.netlify.app/" }
            buildConfigField("String", "FINGERPRINT_API_BASE_URL", quotedBuildConfig(debugUrl))
            buildConfigField("String", "FINGERPRINT_API_ENVIRONMENT", quotedBuildConfig("Development"))
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("String", "FINGERPRINT_API_BASE_URL", quotedBuildConfig(configuredValue("FINGERPRINT_API_BASE_URL")))
            buildConfigField("String", "FINGERPRINT_API_ENVIRONMENT", quotedBuildConfig("Production"))
            optimization {
                enable = false
            }
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.sourceafis)
    implementation(libs.androidx.room.runtime)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.firestore)
    implementation(libs.mlkit.face.detection)
    implementation(libs.opencv)
    annotationProcessor(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
