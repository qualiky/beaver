plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Target JVM 17 bytecode on whichever JDK runs Gradle (no toolchain download needed).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The shared golden fixture (fixtures/golden.json at the repo root). Tests read exactly this file, never a copy;
// declaring it as an input makes Gradle re-run the tests whenever the fixture changes.
val goldenFixture = layout.projectDirectory.file("../../fixtures/golden.json").asFile

tasks.test {
    useJUnitPlatform()
    systemProperty("beaver.fixture.path", goldenFixture.absolutePath)
    inputs.file(goldenFixture).withPropertyName("goldenFixture")
}
