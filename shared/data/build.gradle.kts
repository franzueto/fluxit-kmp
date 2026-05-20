plugins {
    id("fluxit.kmp.library")
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "dev.franzueto.fluxit.shared.data"
}

sqldelight {
    databases {
        create("FluxItDatabase") {
            packageName.set("dev.franzueto.fluxit.shared.data.db")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.bundles.coroutines)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.bundles.sqldelight.runtime)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.bundles.testing.shared)
        }
    }
}
