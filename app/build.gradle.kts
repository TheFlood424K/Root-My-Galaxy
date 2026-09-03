plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.busung.s25uroot"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.busung.s25uroot"
        minSdk = 33
        targetSdk = 36
        versionCode = 13
        versionName = "0.2.66"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
            // Pin to the NDK version pre-installed on ubuntu-latest so AGP
            // never tries to download a different version on CI.
            version = "29.0.14206865"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    buildTypes {
        release {
            // Sign with the debug keystore so CI can produce a complete,
            // installable APK without needing signing secrets.
            signingConfig = signingConfigs.getByName("debug")
            // R8 full-mode (set in gradle.properties) + resource shrinking.
            // Together these are the single biggest contributor to APK size.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // lintVitalRelease runs automatically during assembleRelease and blocks
        // the build on any fatal lint issue. Disable it here so the quick-build
        // workflow can produce an APK even when translations are ahead of the
        // default locale. Lint still runs as a separate CI step via the full
        // CI Build workflow.
        checkReleaseBuilds = false
        // Baseline suppresses pre-existing issues so CI only fails on NEW errors.
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    // Keep extended: all icons used in the app are extended-only.
    // R8 + isShrinkResources strip every unreferenced icon at build time,
    // so the APK only contains the icons actually used.
    implementation("androidx.compose.material:material-icons-extended")
    // 4.2.0 adds ColorSpec.SpecVersion.SPEC_2025 required by AppTheme.kt
    implementation("com.materialkolor:material-kolor:4.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
