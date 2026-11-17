plugins {
    id("sellerflow.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
