plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.parentguard.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.parentguard.agent"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:core-protocol"))
    implementation(project(":core:core-crypto"))
    implementation(project(":core:core-network"))
    implementation(project(":agent:agent-domain"))
    implementation(project(":agent:agent-data"))
    implementation(project(":agent:agent-collectors"))
    implementation(project(":agent:agent-telecom-optin"))
    implementation(project(":agent:agent-sync"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
