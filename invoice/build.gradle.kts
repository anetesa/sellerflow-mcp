plugins {
    id("sellerflow.kotlin-library")
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
