import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Navigation routes are @Serializable classes.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
}

// Sign-in settings come from local.properties (not committed) or -Phomebuddy.<name>=… on the command line.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun homeBuddyProperty(name: String, default: String): String =
    (findProperty("homebuddy.$name") as String?) ?: localProperties.getProperty("homebuddy.$name") ?: default

android {
    namespace = "chat.homebuddy.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "chat.homebuddy.app"
        minSdk = 26
        targetSdk = 36
        // Bump both for every Play release. The server's MIN_ANDROID_VERSION can force older ones to update.
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "COGNITO_DOMAIN", "\"${homeBuddyProperty("cognitoDomain", "")}\"")
        buildConfigField("String", "COGNITO_CLIENT_ID", "\"${homeBuddyProperty("cognitoClientId", "")}\"")
        // Needed by AppAuth's manifest; the redirect filter itself is narrowed in AndroidManifest.xml.
        manifestPlaceholders["appAuthRedirectScheme"] = "homebuddy"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            // 10.0.2.2 is the host machine as seen from the emulator; on a phone, use adb reverse.
            buildConfigField("String", "API_BASE_URL", "\"${homeBuddyProperty("devApiBaseUrl", "http://10.0.2.2:8000")}/\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", "\"https://api.homebuddy.chat/\"")
        }
    }

    // Upload key for Play App Signing, from local.properties or -P. Without it, release builds are unsigned.
    val releaseStoreFile = homeBuddyProperty("releaseStoreFile", "")
    if (releaseStoreFile.isNotEmpty()) {
        signingConfigs.create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = homeBuddyProperty("releaseStorePassword", "")
            keyAlias = homeBuddyProperty("releaseKeyAlias", "upload")
            keyPassword = homeBuddyProperty("releaseKeyPassword", "")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric (screenshots, Room) needs the merged resources.
            isIncludeAndroidResources = true
        }
    }
}

// Reference images live next to the tests and are committed: ./gradlew recordRoborazziDevDebug, then verifyRoborazziDevDebug.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}


// ./gradlew detekt. Findings the code had when detekt was added are in detekt-baseline.xml; new code must pass.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.play.services.code.scanner)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.navigation.testing)
}
