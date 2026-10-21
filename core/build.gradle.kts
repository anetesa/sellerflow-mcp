plugins {
    id("sellerflow.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktoml.core)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
