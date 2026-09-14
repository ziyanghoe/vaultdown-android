plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Public OAuth application identifier; never embed a client secret.
val githubClientId = providers.environmentVariable("GITHUB_CLIENT_ID").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.gradleProperty("githubClientId").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: "Ov23liJfzYP94e0th66a"
require(githubClientId.matches(Regex("[A-Za-z0-9_.-]*"))) { "Invalid GitHub OAuth Client ID" }
android {
    namespace = "dev.vaultdown.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.vaultdown.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.6"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
