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
            // Production builds — must be signed with a release keystore.
            // signingConfig is intentionally NOT set here so the build fails
            // loudly if no signing secrets are configured, rather than silently
            // shipping a debug-signed release APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // ── CI build type ────────────────────────────────────────────────────
        // Installs as a *separate* app (applicationId = dev.busung.s25uroot.ci)
        // so the debug keystore signature never conflicts with a release build.
        // Because the app-id is different, every subsequent CI build can be
        // installed over the previous one with a plain `adb install -r` or
        // a direct APK tap — no uninstall needed.
        //
        // The versionNameSuffix is stamped with the GitHub run number at build
        // time via the GITHUB_RUN_NUMBER environment variable injected by the
        // quick-build workflow, giving each artifact a unique display version
        // (e.g. "0.2.66-ci+42").
        create("ci") {
            initWith(getByName("release"))          // inherit R8 / shrink settings
            applicationIdSuffix = ".ci"
            versionNameSuffix = "-ci+" + (System.getenv("GITHUB_RUN_NUMBER") ?: "local")
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Embed the run number in BuildConfig so the app can surface it
            // in the Settings → About screen.
            buildConfigField(
                "String",
                "CI_RUN_NUMBER",
                "\"" + (System.getenv("GITHUB_RUN_NUMBER") ?: "local") + "\""
            )
        }
    }

    lint {
        checkReleaseBuilds = false
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.materialkolor:material-kolor:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
