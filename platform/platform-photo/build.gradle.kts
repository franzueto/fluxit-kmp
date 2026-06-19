plugins {
    id("fluxit.kmp.library")
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.platform.photo"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // PhotoStorage / PhotoCapture ports + CapturedPhoto/CaptureError model.
            api(project(":shared:domain"))
            implementation(libs.bundles.coroutines)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            // androidContext() in the android `photoModule()` actual.
            implementation(libs.koin.android)
            // ActivityResultRegistry + TakePicture/PickVisualMedia contracts (§7) and
            // FileProvider for the camera temp-file URI.
            implementation(libs.androidx.activity)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // FakePhotoStorage / FakePhotoCapture for any common contract tests.
            implementation(project(":shared:domain-testing"))
        }
        androidHostTest.dependencies {
            // JVM-host Android tests: Robolectric provides a real Context whose
            // filesDir backs the AndroidPhotoStorage round-trip assertions.
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
