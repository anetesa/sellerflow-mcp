plugins {
    id("sellerflow.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)
    testImplementation(project(":invoice"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    // Only the TEST-environment bootstrap signs with XAdES; SellerFlow itself logs in with a KSeF token
    testImplementation(libs.dss.xades)
    testImplementation(libs.dss.token)
    testImplementation(libs.dss.utils)
    testImplementation(libs.bouncycastle.pkix)
}
