plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "jp.rimtty.codematch"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.rimtty.codematch"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release APKs are side-loaded for personal use only; there is no store
    // distribution. By default they are signed with the debug keystore so
    // reinstalls keep working across rebuilds. Supply a local keystore through
    // Gradle properties (for example in ~/.gradle/gradle.properties or
    // ORG_GRADLE_PROJECT_* environment variables) to sign with your own key:
    //   codematchReleaseStoreFile, codematchReleaseStorePassword,
    //   codematchReleaseKeyAlias, codematchReleaseKeyPassword
    val releaseStoreFile = providers.gradleProperty("codematchReleaseStoreFile").orNull
    if (!releaseStoreFile.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = providers.gradleProperty("codematchReleaseStorePassword").orNull
            keyAlias = providers.gradleProperty("codematchReleaseKeyAlias").orNull
            keyPassword = providers.gradleProperty("codematchReleaseKeyPassword").orNull
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            // Strip the Inateck SDK's raw-payload logging and keep JNA/native
            // entry points; see scanner-rules.pro.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "scanner-rules.pro",
            )
            // The official SDK ships arm64-v8a native libraries only.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:matching"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:export"))
    implementation(project(":feature:scan"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))
    implementation(project(":scanner:api"))
    implementation(project(":scanner:camera"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.dagger.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.adaptive.navigation.suite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    debugImplementation(project(":scanner:fake"))
    releaseImplementation(project(":scanner:inateck"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.datastore.preferences)

    ksp(libs.dagger.hilt.compiler)
}
