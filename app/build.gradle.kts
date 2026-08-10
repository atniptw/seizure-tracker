import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.atnip.seizuretracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.atnip.seizuretracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            // If app/debug.keystore is present, pin signing to it instead of AGP's
            // implicit ~/.android/debug.keystore. CI writes this file from a secret
            // (gitignored, never committed — see README's CI/CD section) so every CI
            // run reuses the same cert; otherwise a fresh one per ephemeral runner
            // would break installs over prior Firebase App Distribution builds and
            // Google Sign-In's SHA-1 registration. Local dev falls back to the
            // default debug keystore when this file isn't present.
            val pinnedDebugKeystore = file("debug.keystore")
            if (pinnedDebugKeystore.exists()) {
                storeFile = pinnedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release keystore yet — debug-sign so CD can produce an installable APK.
            // Swap this for a real signingConfig before shipping to the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // DataStore for local prefs (household id, display name)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    // Google Sign-In via Credential Manager
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Coroutines <-> Play services Tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests
    testImplementation("junit:junit:4.13.2")

    // Phase 3: repository/ViewModel integration tests against the Firebase Local Emulator
    // Suite. Robolectric supplies a real Android Context (Firestore/Auth/DataStore all need
    // one) so these run as plain JVM tests, no device/emulator required — the emulator here
    // refers to Firebase's, not an Android one.
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Phase 4: Compose UI tests for real screens, driven under Robolectric (no device/AVD
    // needed) rather than instrumented androidTest. Versions come from the compose-bom
    // platform import above.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
