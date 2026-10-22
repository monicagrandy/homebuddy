plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "chat.homebuddy.feature.auth"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 26
        // AppAuth's manifest needs it, including in this module's unit-test manifest.
        manifestPlaceholders["appAuthRedirectScheme"] = "homebuddy"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric (screenshots) needs the merged resources.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:auth"))
    api(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Reference images live next to the tests and are committed: ./gradlew recordRoborazziDebug, then verifyRoborazziDebug.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
