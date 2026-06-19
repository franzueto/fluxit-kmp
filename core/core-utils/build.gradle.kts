plugins {
    id("fluxit.kmp.library")
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.core.utils"
    }
}

kotlin {
    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
