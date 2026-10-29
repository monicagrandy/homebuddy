plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt) apply false
}

// The API contract recorded by the backend (../contracts). Tests read it, and rerun when it changes.
subprojects {
    tasks.withType<Test>().configureEach {
        val contracts = rootProject.file("../contracts")
        systemProperty("homebuddy.contracts", contracts.absolutePath)
        inputs.dir(contracts).withPropertyName("contracts").optional()
    }
}
