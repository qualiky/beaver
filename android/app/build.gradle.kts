plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "au.com.fuzn.beaver"
    compileSdk = 37

    defaultConfig {
        applicationId = "au.com.fuzn.beaver"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Room exports its schema JSON here; the files are committed so schema changes show in review.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The shared cross-platform backup fixture (fixtures/demo-backup-v1.json at the repo root). Tests read exactly this file.
val backupFixture = layout.projectDirectory.file("../../fixtures/demo-backup-v1.json").asFile
// Optional: ./gradlew :app:testDebugUnitTest -Pbeaver.private.backup=/path/to/backup.json (never commit that file).
val privateBackup = providers.gradleProperty("beaver.private.backup")

tasks.withType<Test>().configureEach {
    systemProperty("beaver.fixture.backup", backupFixture.absolutePath)
    privateBackup.orNull?.let { systemProperty("beaver.private.backup", it) }
    inputs.file(backupFixture).withPropertyName("backupFixture")
    // Show the optional private check's printed result in the console.
    testLogging.showStandardStreams = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
