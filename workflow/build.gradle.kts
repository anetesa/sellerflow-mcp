plugins {
    id("sellerflow.kotlin-library")
}

// The workflow talks to Allegro, InPost and KSeF through interfaces declared in :core,
// so it never depends on the client modules directly.
dependencies {
    implementation(project(":core"))
    implementation(project(":storage"))
    implementation(project(":invoice"))
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}
