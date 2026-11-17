plugins {
    id("sellerflow.kotlin-library")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}
