// Shared setup for every module: Kotlin/JVM 21, tests on JUnit Platform, detekt and ktlint.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "io.github.anetesa.sellerflow"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Sandbox integration tests talk to real test environments: run them only with -Pintegration
    if (!project.hasProperty("integration")) {
        systemProperty("kotest.tags", "!Integration")
    }
}
