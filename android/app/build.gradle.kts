plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.fitnotes"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = if (providers.gradleProperty("photoVerification").orNull == "true") "com.example.fitnotes.preview.photos" else "com.example.fitnotes"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.0-beta.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                // Partial library optimization breaks Kotlin class access at
                // startup. Keep the first beta unoptimized and non-debuggable.
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets.getByName("main").assets.srcDir("../../data/programs")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The public JSON is the source of truth; bundle three exact starter files.
val syncStarterCatalog = tasks.register<Sync>("syncStarterCatalog") {
    from("../../web/public/catalog") {
        include("manifest.json", "exercises.json", "LICENSE.txt",
            "programs/full-body-beginner-3day-v1.json",
            "programs/full-body-express-2day-v1.json",
            "programs/dumbbell-home-3day-v1.json")
    }
    into(layout.buildDirectory.dir("generated/catalogAssets/catalog"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/catalogAssets").get().asFile)
tasks.named("preBuild").configure { dependsOn(syncStarterCatalog) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("androidx.work:work-testing:2.12.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.icons)
    testImplementation(libs.json.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("androidx.health.connect:connect-testing:1.0.0-alpha02")
    androidTestImplementation(libs.androidx.room.testing)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
