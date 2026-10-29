// Everything below the UI: network, auth, data and domain. It knows nothing about Compose, so
// screens can only reach it through repositories and use cases, never DTOs or Retrofit directly.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

android {
    namespace = "chat.homebuddy.core"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 26
        // AppAuth's redirect activity is declared with this scheme; ours replaces its intent filter
        // (see AndroidManifest.xml) so only homebuddy://auth/callback reaches it.
        manifestPlaceholders["appAuthRedirectScheme"] = "homebuddy"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            // Robolectric (Room in memory) needs the merged resources.
            isIncludeAndroidResources = true
        }
    }
    // Shared with :app's tests: the test doubles, and ContractFixtures (which reads ../contracts).
    testFixtures {
        enable = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}


// ./gradlew detekt. Findings the code had when detekt was added are in detekt-baseline.xml; new code must pass.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

dependencies {
    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.appauth)
    // SignInPage is an ActivityResultContract, so the screen never handles the page's Intents itself.
    implementation(libs.androidx.activity)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    testFixturesImplementation(libs.kotlinx.coroutines.android)
    testFixturesImplementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
}
