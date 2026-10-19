plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseStoreFile = System.getenv("NIGHTWATCH_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("NIGHTWATCH_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("NIGHTWATCH_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("NIGHTWATCH_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val nightwatchVersionCode = providers.gradleProperty("nightwatchVersionCode").orNull?.toIntOrNull() ?: 100
val nightwatchVersionName = providers.gradleProperty("nightwatchVersionName").orNull ?: "0.1.0"
// -PnightwatchScreenshots=true lifts FLAG_SECURE in debug builds (emulator testing).
val nightwatchScreenshots = providers.gradleProperty("nightwatchScreenshots").orNull == "true"
if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Release signing requires all NIGHTWATCH_RELEASE_* environment variables.")
}
android {
    namespace = "dev.cybercore.nightwatch"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.cybercore.nightwatch"
        minSdk = 30
        targetSdk = 36
        versionCode = nightwatchVersionCode
        versionName = nightwatchVersionName
    }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ALLOW_SCREENSHOTS", nightwatchScreenshots.toString())
            buildConfigField("boolean", "EMULATOR_HOST", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_SCREENSHOTS", "false")
            buildConfigField("boolean", "EMULATOR_HOST", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        warningsAsErrors = true
        informational += setOf("OldTargetApi", "AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.unifiedpush.android:connector:3.3.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
