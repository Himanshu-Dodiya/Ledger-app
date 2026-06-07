import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services") // reads app/google-services.json
}

// Backend/auth config lives in local.properties (never committed). Falls back to env vars
// (useful for CI) and finally to an empty string so the project still configures.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: default

android {
    namespace = "com.ledger.collector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ledger.collector"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Supabase + backend wiring. Set these in local.properties:
        //   SUPABASE_URL=https://xxxx.supabase.co
        //   SUPABASE_ANON_KEY=ey...
        //   API_BASE_URL=https://your-ledger.vercel.app   (no trailing slash)
        //   GOOGLE_WEB_CLIENT_ID=...apps.googleusercontent.com  (the Supabase Google "Web" client id)
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "API_BASE_URL", "\"${cfg("API_BASE_URL")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${cfg("GOOGLE_WEB_CLIENT_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Provides the Material 3 (DayNight) base XML theme for the activity.
    implementation("com.google.android.material:material:1.12.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle / ViewModel / Compose state
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Supabase (auth + PostgREST). BOM 3.0.0 is built against Kotlin 2.0.x, matching this
    // project's compiler — newer 3.x is built with Kotlin 2.1+/2.3+ and won't compile here.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    // Ktor engine that supabase-kt runs on (kept in lockstep with BOM 3.0.0 → Ktor 3.0.0).
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Direct backend calls to the Next.js API (POST /api/sms, PATCH/DELETE /api/transactions).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google sign-in via Credential Manager (returns a Google ID token for Supabase).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Legacy GMS Sign-In: used exclusively for requesting the Gmail serverAuthCode + gmail.readonly
    // scope that the Go backend exchanges for a refresh token. The Credential Manager API does not
    // support requesting arbitrary OAuth scopes.
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Firebase: push notifications via FCM HTTP v1 API (server-side) + on-device token.
    // The BOM pins all firebase-* versions together.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    testImplementation("junit:junit:4.13.2")
}
