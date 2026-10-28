plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.parentguard.agent.sync"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:core-protocol"))
    implementation(project(":core:core-crypto"))
    implementation(project(":core:core-network"))
    implementation(project(":agent:agent-domain"))
    implementation(project(":agent:agent-data"))
    implementation(project(":agent:agent-collectors"))
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
