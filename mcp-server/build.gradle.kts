plugins {
    id("sellerflow.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("io.github.anetesa.sellerflow.mcp.MainKt")
    applicationName = "sellerflow"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":workflow"))
    implementation(project(":allegro"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.mcp.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)
    // stdio carries the MCP protocol on stdout: logs must go to stderr, which slf4j-simple does by default
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.mcp.client)
    testImplementation(libs.mcp.testing)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}
