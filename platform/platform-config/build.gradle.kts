plugins {
    id("fluxit.kmp.library")
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.platform.config"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ConfigProvider/ConfigKey + the Clock port live in :shared:domain;
            // this module ships their production bindings. `api` so the port
            // types stay visible to Koin consumers resolving from `configModule`.
            api(project(":shared:domain"))
            // IdGenerator.System (the UUID-v4 actual) lives in :core:core-utils
            // per ADR-006a; configModule binds it rather than re-deriving UUID
            // generation here. `api` so consumers see the aliased port type.
            api(project(":core:core-utils"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
